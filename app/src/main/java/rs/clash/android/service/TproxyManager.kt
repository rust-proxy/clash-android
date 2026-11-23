package rs.clash.android.service

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import kotlin.system.exitProcess

/** Short-lived KernelSU command. The module launches the installed APK's native core. */
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
					val status = TproxySocket.request(control, uid)
					when (action) {
						"recover" -> if (status?.ready != true || !rules.installed) stop(rules, control, uid)
						"stop" -> stop(rules, control, uid)
						"start", "restart" -> {
							if (action == "restart" || status?.ready != true || status.ownerUid != 0 || !rules.installed) {
								val launcher = moduleLauncher(abi)
								stop(rules, control, uid)
								start(launcher, library, control, uid, config, rules)
							}
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

	private fun moduleLauncher(abi: String): File {
		require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64"))
		val active = File("/data/adb/modules/clash_android_tproxy")
		check(!File(active, "disable").exists() && !File(active, "remove").exists()) { "KernelSU module disabled or removed" }
		// KernelSU stages first installs until reboot. Use the installed version during upgrades.
		val module =
			if (!File(active, "bin").exists() && File(active, "update").exists()) {
				File("/data/adb/modules_update/clash_android_tproxy")
			} else {
				active
			}
		val launcher = File(module, "bin/$abi/clash-tproxy")
		check(File(module, "protocol-1").isFile && launcher.isFile && launcher.canExecute()) { "Install the Clash Android TPROXY module" }
		return launcher
	}

	private fun start(
		launcher: File,
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
				"clash-tproxy",
				launcher.path,
				uid.toString(),
				library,
			).start()
		try {
			child.outputStream.bufferedWriter().use { it.write(config.toString()) }
			val deadline = SystemClock.elapsedRealtime() + 30000
			while (true) {
				check(runCatching { child.exitValue() }.isFailure) { "Native launcher exited" }
				val status = TproxySocket.request(control, uid)
				if (status?.ownerUid == 0 && listening(status, config)) break
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
		status: NativeStatus,
		config: JSONObject,
	): Boolean {
		val proc = File("/proc/${status.pid}")
		val sockets =
			File(proc, "fd")
				.listFiles()
				.orEmpty()
				.mapNotNull {
					runCatching { android.system.Os.readlink(it.path) }.getOrNull()
				}.toSet()
		val ipv6 = config.getBoolean("ipv6")
		val systemDns = config.getBoolean("systemDns")
		val backend = status.tcpDnsBackend
		if (systemDns && backend == null) return false
		val protocols = if (ipv6) listOf("tcp", "udp", "tcp6", "udp6") else listOf("tcp", "udp")
		val expected = mutableMapOf<String, MutableSet<String>>()
		for (protocol in protocols) {
			val loopback = if (protocol.endsWith("6")) "00000000000000000000000001000000" else "0100007F"
			val endpoints = expected.getOrPut(protocol) { mutableSetOf() }

			fun endpoint(port: Int) = "%s:%04X".format(java.util.Locale.ROOT, loopback, port)

			endpoints.add(endpoint(TproxySocket.PORT))
			if (systemDns && protocol.startsWith("tcp")) endpoints.add(endpoint(TproxySocket.TCP_DNS_PORT))
		}
		if (backend != null) {
			val protocol = if (backend.substringBefore(':').length == 32) "tcp6" else "tcp"
			expected.getOrPut(protocol) { mutableSetOf() }.add(backend)
		}
		return expected.all { (protocol, endpoints) ->
			val state = if (protocol.startsWith("tcp")) "0A" else "07"
			File(proc, "net/$protocol").useLines { lines ->
				for (line in lines) {
					val fields = line.trim().split(Regex("\\s+"))
					if (fields.size > 9 && fields[3] == state && "socket:[${fields[9]}]" in sockets) {
						endpoints.remove(fields[1])
					}
				}
			}
			endpoints.isEmpty()
		}
	}
}
