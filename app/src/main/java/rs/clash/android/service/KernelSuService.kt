package rs.clash.android.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

class KernelSuService : Service() {
	companion object {
		internal const val MODE_KEY = "kernel_su_tproxy"
	}

	private val session = ProxyServiceSession(this, ::runSession)

	override fun onBind(intent: Intent): IBinder? = session.bind(intent)

	override fun onUnbind(intent: Intent): Boolean {
		session.unbind(intent)
		return super.onUnbind(intent)
	}

	override fun onStartCommand(
		intent: Intent?,
		flags: Int,
		startId: Int,
	): Int = session.start(intent)

	override fun onDestroy() {
		session.stop()
		super.onDestroy()
	}

	private suspend fun runSession(
		config: JSONObject,
		ready: (Int) -> Unit,
	) = withContext(Dispatchers.IO) {
		val info = applicationInfo
		val apks = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
		val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
		val libraryDir =
			apks.firstNotNullOfOrNull { path ->
				ZipFile(path).use { zip ->
					abis.firstOrNull { zip.getEntry("lib/$it/libclash_android_ffi.so") != null }?.let { "$path!/lib/$it" }
				}
			}
		val libs = if (File(info.nativeLibraryDir, "libclash_android_ffi.so").isFile) info.nativeLibraryDir else checkNotNull(libraryDir)
		val runtime = "/system/bin/app_process" + if (Process.is64Bit()) "64" else "32"
		val args =
			listOf(
				runtime,
				"-Djava.library.path=$libs",
				"/system/bin",
				"--nice-name=clash-tproxy-rules",
				TproxySupervisor::class.java.name,
				libs,
				runtime,
				packageName,
				Process.myUid().toString(),
			)

		// Only package-manager paths and process metadata enter the shell command.
		// Profile data is sent on a private pipe after authorization.
		fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
		val command = "export CLASSPATH=${quote(apks.joinToString(":"))}; exec " + args.joinToString(" ", transform = ::quote)
		val apps = config.getJSONArray("apps")
		val uids =
			(0 until apps.length())
				.mapNotNull {
					try {
						packageManager.getApplicationInfo(apps.getString(it), 0).uid
					} catch (_: PackageManager.NameNotFoundException) {
						null
					}
				}.distinct()
		config.put("uids", JSONArray(uids)).put("workDir", cacheDir.path)
		val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
		try {
			process.outputStream.write((config.toString() + "\n").toByteArray())
			process.outputStream.flush()
			coroutineScope {
				val reader =
					async(Dispatchers.IO) {
						process.inputStream.bufferedReader().useLines { lines ->
							for (line in lines) {
								if (line.startsWith("READY ")) {
									val port = line.removePrefix("READY ").toInt()
									withContext(Dispatchers.Main) { ready(port) }
								}
							}
						}
						check(process.waitFor() == 0) { "KernelSU session failed" }
					}
				try {
					reader.await()
				} finally {
					process.outputStream.close()
				}
			}
		} finally {
			process.outputStream.close()
			withContext(NonCancellable) { process.waitFor() }
		}
		Unit
	}
}
