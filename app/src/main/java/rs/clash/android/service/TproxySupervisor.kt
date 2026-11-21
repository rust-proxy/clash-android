package rs.clash.android.service

import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Root entry point. The service owns its lifetime via stdin, including on binder/process death. */
internal object TproxySupervisor {
	@JvmStatic
	fun main(args: Array<String>) {
		try {
			check(Process.myUid() == 0)
			supervise(args)
			exitProcess(0)
		} catch (e: Throwable) {
			Log.e("ClashTproxy", "KernelSU session failed", e)
			exitProcess(1)
		}
	}

	private fun supervise(args: Array<String>) {
		val input = System.`in`.bufferedReader()
		val config = JSONObject(input.readLine() ?: return)
		val uid = args[3].toInt()
		val stopped = CountDownLatch(1)
		val ownerStopped = AtomicBoolean()
		thread(isDaemon = true) {
			try {
				input.readLine()
			} finally {
				ownerStopped.set(true)
				stopped.countDown()
			}
		}
		val child = ProcessBuilder(TproxyCore.command(args)).redirectErrorStream(true).start()
		val announced = AtomicReference<Pair<Int, Int>>()
		val rules = TproxyRules()
		try {
			child.outputStream.write((config.toString() + "\n").toByteArray())
			child.outputStream.flush()
			thread(isDaemon = true) {
				try {
					child.inputStream.bufferedReader().useLines { lines ->
						for (line in lines) {
							if (line.startsWith("CORE ")) {
								val (_, pid, port) = line.split(' ')
								announced.set(pid.toInt() to port.toInt())
							}
						}
					}
				} finally {
					stopped.countDown()
				}
			}
			val deadline = SystemClock.elapsedRealtime() + 30000
			while (true) {
				if (stopped.await(100, TimeUnit.MILLISECONDS)) {
					check(ownerStopped.get()) { "Core failed to start" }
					return
				}
				val core = announced.get()
				if (core != null && listening(core.first, config.getBoolean("ipv6"))) {
					rules.install(config, uid)
					println("READY ${core.second}")
					stopped.await()
					check(ownerStopped.get()) { "Proxy core exited unexpectedly" }
					return
				}
				check(SystemClock.elapsedRealtime() < deadline) { "Transparent listeners did not start" }
			}
		} finally {
			try {
				rules.remove()
			} finally {
				child.outputStream.close()
				val until = SystemClock.elapsedRealtime() + 3000
				while (runCatching { child.exitValue() }.isFailure && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
				if (android.os.Build.VERSION.SDK_INT >= 26) child.destroyForcibly() else child.destroy()
				child.waitFor()
			}
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
		val port = ":%04X".format(java.util.Locale.ROOT, TproxyCore.PORT)
		val protocols = if (ipv6) listOf("tcp", "udp", "tcp6", "udp6") else listOf("tcp", "udp")
		return protocols.all { protocol ->
			File(proc, "net/$protocol").useLines { lines ->
				lines.any { line ->
					val fields = line.trim().split(Regex("\\s+"))
					fields.size > 9 &&
						fields[1].endsWith(port) &&
						fields[3] == (if (protocol.startsWith("tcp")) "0A" else "07") &&
						"socket:[${fields[9]}]" in sockets
				}
			}
		}
	}
}
