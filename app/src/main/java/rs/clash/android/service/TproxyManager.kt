package rs.clash.android.service

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/** Short-lived KernelSU command. The installed APK is the only source of executable code. */
internal object TproxyManager {
	@JvmStatic
	fun main(args: Array<String>) {
		try {
			check(Process.myUid() == 0)
			val (action, rawUid, abi, library, control) = args
			val uid = rawUid.toInt()
			require(uid >= 10000)
			val config = JSONObject(System.`in`.bufferedReader().readText())
			val dir = File("/data/adb/clash-android/$uid")
			check(dir.isDirectory || dir.mkdirs())
			Os.chmod(dir.path, 448) // 0700
			RandomAccessFile(File(dir.parentFile, "manager.lock"), "rw").use { file ->
				file.channel.lock().use {
					val rules = TproxyRules(dir)
					var status = TproxySocket.request(control, uid)
					when (action) {
						"recover" -> if (status?.ready != true || !rules.installed) stop(rules, control, uid)
						"stop" -> stop(rules, control, uid)
						"start", "restart" -> {
							if (action == "restart" || status?.ready != true || !rules.installed) {
								stop(rules, control, uid)
								status = null
							}
							if (status == null) start(dir, abi, library, control, uid, config, rules)
						}
						else -> error("Unknown TPROXY operation")
					}
				}
			}
			exitProcess(0)
		} catch (e: Throwable) {
			Log.e("ClashTproxy", "KernelSU controller operation failed", e)
			exitProcess(1)
		}
	}

	private fun stop(
		rules: TproxyRules,
		control: String,
		uid: Int,
	) {
		val peer = TproxySocket.request(control, uid)
		rules.remove()
		if (peer != null) TproxySocket.request(control, uid, 'X')
		// Wait for the old process to release native sockets before a restart.
		if (peer != null) {
			val old = File("/proc/${peer.pid}/stat")
			val until = SystemClock.elapsedRealtime() + 5000
			while (runCatching { old.readText().substringAfterLast(") ").first() }.getOrNull()?.let { it != 'Z' } == true) {
				check(SystemClock.elapsedRealtime() < until) { "Core did not stop" }
				Thread.sleep(30)
			}
		}
	}

	private fun launcher(
		dir: File,
		abi: String,
	): File {
		val dest = File(dir, "clash-launcher")
		val temp = File.createTempFile("launcher-", null, dir)
		try {
			val installed = checkNotNull(System.getenv("CLASSPATH")).split(':')
			check(
				installed.any { path ->
					ZipFile(path).use { zip ->
						val entry = zip.getEntry("assets/tproxy/$abi/clash-launcher") ?: return@use false
						zip.getInputStream(entry).use { input -> temp.outputStream().use { input.copyTo(it) } }
						true
					}
				},
			) { "Launcher missing from installed APK" }
			Os.chmod(temp.path, 448)
			Os.rename(temp.path, dest.path)
			return dest
		} finally {
			temp.delete()
		}
	}

	private fun start(
		dir: File,
		abi: String,
		library: String,
		control: String,
		uid: Int,
		config: JSONObject,
		rules: TproxyRules,
	) {
		val child =
			ProcessBuilder(
				"/system/bin/sh",
				"-c",
				"""exec "$@" >/dev/null 2>&1""",
				"clash-launcher",
				launcher(dir, abi).path,
				uid.toString(),
				library,
			).start()
		try {
			child.outputStream.bufferedWriter().use { it.write(config.toString()) }
			val deadline = SystemClock.elapsedRealtime() + 30000
			while (true) {
				check(runCatching { child.exitValue() }.isFailure) { "Native launcher exited" }
				val status = TproxySocket.request(control, uid)
				if (status != null && listening(status.pid, config.getBoolean("ipv6"))) break
				check(SystemClock.elapsedRealtime() < deadline) { "Transparent listeners did not start" }
				Thread.sleep(100)
			}
			rules.install(config, uid)
			check(TproxySocket.request(control, uid, 'A')?.ready == true)
		} catch (e: Throwable) {
			// If cleanup fails, retain the listener until the next recovery can
			// remove the remaining redirects. The journal records that work.
			rules.remove()
			child.destroy()
			child.waitFor()
			throw e
		}
	}

	// Verify the socket inode belongs to this core, even if another app owns the same port.
	private fun listening(
		pid: Int,
		ipv6: Boolean,
	): Boolean {
		val proc = File("/proc/$pid")
		val sockets =
			File(proc, "fd")
				.listFiles()
				.orEmpty()
				.mapNotNull {
					runCatching { android.system.Os.readlink(it.path) }.getOrNull()
				}.toSet()
		val port = ":%04X".format(java.util.Locale.ROOT, TproxySocket.PORT)
		val protocols = if (ipv6) listOf("tcp", "udp", "tcp6", "udp6") else listOf("tcp", "udp")
		return protocols.all { protocol ->
			// /proc/net prints each address word in host byte order.
			val address = if (protocol.endsWith("6")) "00000000000000000000000001000000" else "0100007F"
			File(proc, "net/$protocol").useLines { lines ->
				lines.any { line ->
					val fields = line.trim().split(Regex("\\s+"))
					fields.size > 9 &&
						fields[1] == address + port &&
						fields[3] == (if (protocol.startsWith("tcp")) "0A" else "07") &&
						"socket:[${fields[9]}]" in sockets
				}
			}
		}
	}
}
