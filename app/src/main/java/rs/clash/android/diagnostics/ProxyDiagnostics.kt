package rs.clash.android.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.firebase.crashlytics.CustomKeysAndValues
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rs.clash.android.BuildConfig
import java.io.File
import java.io.IOException

internal object ProxyDiagnostics {
	private const val TAG = "ProxyDiagnostics"
	private const val RETENTION = 14L * 24 * 60 * 60 * 1000
	private const val MAX_RUNS = 32
	private const val MAX_TRACE_BYTES = 4L * 1024 * 1024
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val lock = Mutex()
	private var session: File? = null
	private var remainingReports = 6

	private fun root(context: Context) = File(context.noBackupFilesDir, "proxy-diagnostics")

	/** Called only in :proxy, before loading the native library. Each process owns its own directory. */
	fun begin(context: Context): String? =
		try {
			val dir = File(root(context), "${Process.myPid()}-${System.currentTimeMillis()}")
			check(dir.mkdirs())
			// Translated apps can run arm64 code on a device whose preferred ABI is x86_64.
			val abi =
				when (val arch = File(context.applicationInfo.nativeLibraryDir).name) {
					"arm64" -> "arm64-v8a"
					"arm" -> "armeabi-v7a"
					"x86", "x86_64" -> arch
					else -> (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS).firstOrNull() ?: "unknown"
				}
			File(dir, "version").writeText("${BuildConfig.VERSION_NAME}\n$abi")
			val info = context.applicationInfo
			File(dir, "apks").writeText((listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).joinToString("\n"))
			session = dir
			// Always-on VPN can restart repeatedly without ever opening an Activity.
			root(context)
				.listFiles()
				.orEmpty()
				.filter { it.isDirectory && it != dir }
				.sortedByDescending { it.name.substringAfter('-').toLongOrNull() ?: 0 }
				.drop(MAX_RUNS - 1)
				.forEach { it.deleteRecursively() }
			if (Build.VERSION.SDK_INT >= 30) {
				context.getSystemService(ActivityManager::class.java).setProcessStateSummary(dir.name.toByteArray())
			}
			File(dir, "panic").absolutePath
		} catch (e: Exception) {
			Log.w(TAG, "Could not initialize proxy diagnostics", e)
			session?.let { File(it, "panic").absolutePath }
		}

	fun markStopped() {
		try {
			session?.let { File(it, "stopped").createNewFile() }
		} catch (_: IOException) {
			// Diagnostic I/O must never prevent VPN teardown.
		}
	}

	fun recover(
		context: Context,
		afterDisconnect: Boolean = false,
	) {
		if (!BuildConfig.FIREBASE_ENABLED) return
		val app = context.applicationContext
		scope.launch {
			// The binder death notification can precede publication of the system exit record.
			if (afterDisconnect) delay(5_000)
			lock.withLock {
				try {
					recoverRuns(app)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Log.w(TAG, "Could not recover proxy diagnostics", e)
				}
			}
		}
	}

	private fun recoverRuns(context: Context) {
		val now = System.currentTimeMillis()
		val runs =
			root(context).listFiles().orEmpty().filter { it.isDirectory }.sortedByDescending {
				it.name.substringAfter('-').toLongOrNull() ?: 0
			}
		val exits = if (Build.VERSION.SDK_INT >= 30 && runs.isNotEmpty()) recentExits(context) else emptyList()
		val nextStartForPid = mutableMapOf<Int, Long>()
		for (dir in runs) {
			val start = dir.name.substringAfter('-').toLongOrNull() ?: continue
			val pid = dir.name.substringBefore('-').toIntOrNull() ?: continue
			val nextStart = nextStartForPid.put(pid, start) ?: Long.MAX_VALUE
			val exit = exits.firstOrNull { it.runId == dir.name || (it.runId == null && it.pid == pid && it.time in start until nextStart) }
			val panic = File(dir, "panic")
			val age = now - maxOf(start, panic.lastModified(), exit?.time ?: 0)
			// A long-lived VPN may still be writing to its run directory.
			if (age > RETENTION) {
				val alive =
					context
						.getSystemService(ActivityManager::class.java)
						.runningAppProcesses
						.orEmpty()
						.any { it.pid == pid && it.processName == "${context.packageName}:proxy" }
				if (!alive) dir.deleteRecursively()
				continue
			}
			val native = exit?.native == true
			val marker = File(dir, if (native) "reported-native" else "reported-panic")
			if (remainingReports == 0 || marker.exists() || File(dir, "reported-native").exists()) continue
			if (!native && !panic.isFile) continue
			// Give a simultaneous fatal exit time to appear before classifying a panic on its own.
			if (!native && exit == null && now - panic.lastModified() < 5_000) continue
			val version = File(dir, "version").takeIf { it.isFile }?.readLines().orEmpty()
			val location = panic.takeIf { it.isFile }?.readText()?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]{1,128}:[0-9]{1,10}")) }
			val trace = if (native) exit.readTrace(dir) else null
			val keys =
				CustomKeysAndValues
					.Builder()
					.putString("origin_process", "proxy")
					.putString("kind", if (native) "proxy_native_crash" else "proxy_rust_panic")
					.putString("report_id", dir.name)
					.putString("original_version", version.firstOrNull() ?: "unknown")
					.putString("abi", version.getOrNull(1) ?: "unknown")
					.putLong("occurred_at_ms", if (native) exit.time else panic.lastModified())
					.putBoolean("shutdown_completed", File(dir, "stopped").exists())
			location?.let { keys.putString("panic_location", it) }
			if (native) keys.putInt("exit_status", exit.status)
			trace?.let {
				keys.putInt("signal", it.signal)
				keys.putInt("crashing_tid", it.threadId)
				it.frames.forEachIndexed { n, frame -> keys.putString("native_frame_$n", frame) }
			}
			val error = if (native) ProxyNativeCrashException() else ProxyRustPanicException()
			FirebaseCrashlytics.getInstance().recordException(error, keys.build())
			remainingReports--
			marker.createNewFile()
		}
	}

	private class ProxyNativeCrashException : RuntimeException("Native crash in VPN process")

	private class ProxyRustPanicException : RuntimeException("Rust panic observed in VPN process")

	private data class Exit(
		val pid: Int,
		val time: Long,
		val runId: String?,
		val native: Boolean,
		val status: Int,
		val readTrace: (File) -> NativeTrace?,
	)

	@RequiresApi(30)
	private fun recentExits(context: Context): List<Exit> =
		context
			.getSystemService(ActivityManager::class.java)
			.getHistoricalProcessExitReasons(context.packageName, 0, 64)
			.filter { it.processName == "${context.packageName}:proxy" }
			.map { info ->
				Exit(
					info.pid,
					info.timestamp,
					info.processStateSummary?.decodeToString(),
					info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE,
					info.status,
				) { dir ->
					if (Build.VERSION.SDK_INT >= 31) readTrace(info, dir) else null
				}
			}

	@RequiresApi(31)
	private fun readTrace(
		info: ApplicationExitInfo,
		dir: File,
	): NativeTrace? {
		val file = File(dir, "tombstone.pb")
		val pending = File(dir, "tombstone.tmp")
		try {
			if (!file.isFile) {
				info.traceInputStream?.use { input ->
					pending.outputStream().buffered().use { output ->
						val buffer = ByteArray(8192)
						var total = 0L
						while (true) {
							val count = input.read(buffer)
							if (count == -1) break
							total += count
							if (total > MAX_TRACE_BYTES) throw IOException("Oversized native trace")
							output.write(buffer, 0, count)
						}
					}
					if (!pending.renameTo(file)) throw IOException("Could not save native trace")
				}
			}
			val apkPaths =
				File(dir, "apks")
					.takeIf { it.isFile }
					?.readLines()
					.orEmpty()
					.toSet()
			return file.takeIf { it.isFile }?.inputStream()?.use { NativeTrace.read(it, apkPaths) }
		} catch (_: IOException) {
			return null
		} finally {
			pending.delete()
		}
	}
}
