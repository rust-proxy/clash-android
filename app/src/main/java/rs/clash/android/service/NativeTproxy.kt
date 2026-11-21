package rs.clash.android.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import rs.clash.android.R
import rs.clash.android.ui.snackbar.SnackbarController
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

internal data class NativeStatus(
	val pid: Int,
	val ready: Boolean,
	val mixedPort: Int,
)

internal object TproxySocket {
	const val PORT = 17893

	fun request(
		path: String,
		uid: Int,
		command: Char = '?',
	): NativeStatus? {
		LocalSocket().use { socket ->
			try {
				socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
			} catch (_: IOException) {
				return null
			}
			socket.soTimeout = 6500
			val peer = socket.peerCredentials
			check(peer.uid == 9999 && peer.gid == uid) { "Unexpected TPROXY socket owner" }
			socket.outputStream.write(command.code)
			val reply = socket.inputStream.bufferedReader().readLine() ?: error("Core disconnected")
			if (reply == "STOPPED") return null
			val (phase, port) = reply.split(' ')
			check(phase == "STARTING" || phase == "READY")
			return NativeStatus(peer.pid, phase == "READY", port.toInt())
		}
	}
}

/** The app holds a connection only while making a request. Closing the UI cannot stop the core. */
internal class NativeTproxy(
	private val context: Context,
	private val scope: CoroutineScope,
) {
	companion object {
		const val MODE_KEY = "kernel_su_tproxy"
		private const val REQUESTED = "native_tproxy_requested"
		private const val IN_FLIGHT = "native_tproxy_changing"
	}

	private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
	private val control = File(context.noBackupFilesDir, "tproxy.sock").path
	private val serial = Mutex()
	private val snapshot = MutableStateFlow(VpnSessionState(running = prefs.getBoolean(REQUESTED, false)))
	val state = snapshot.asStateFlow()
	private var observers = 0
	private var monitor: Job? = null

	fun attach() {
		if (++observers != 1) return
		monitor =
			scope.launch {
				var recover = true
				while (isActive) {
					if (prefs.getBoolean(MODE_KEY, false) || prefs.getBoolean(REQUESTED, false)) {
						serial.withLock {
							try {
								var status = query()
								report(status)
								if (status?.ready == true && !prefs.getBoolean(IN_FLIGHT, false)) {
									recover = true
								} else if (recover && prefs.getBoolean(REQUESTED, false)) {
									// Reconcile interrupted rule transactions once per attach or lost core.
									recover = false
									withContext(Dispatchers.IO) { manage("recover", JSONObject()) }
									status = query()
									prefs
										.edit()
										.putBoolean(REQUESTED, status != null)
										.remove(IN_FLIGHT)
										.apply()
									report(status)
								}
							} catch (e: CancellationException) {
								throw e
							} catch (e: Exception) {
								Log.w("ClashTproxy", "Could not reconcile native session", e)
							}
						}
					}
					delay(2000)
				}
			}
	}

	fun detach() {
		if (--observers == 0) monitor?.cancel()
	}

	private suspend fun query() = withContext(Dispatchers.IO) { TproxySocket.request(control, Process.myUid()) }

	private fun report(status: NativeStatus?) {
		snapshot.value = if (status == null) VpnSessionState() else VpnSessionState(true, status.mixedPort.takeIf { it > 0 }, status.ready)
	}

	suspend fun change(
		action: String,
		config: JSONObject = JSONObject(),
	) = serial.withLock {
		try {
			if (action != "stop") {
				snapshot.value = VpnSessionState(running = true)
			}
			withContext(Dispatchers.IO) {
				check(
					prefs
						.edit()
						.putBoolean(REQUESTED, true)
						.putBoolean(IN_FLIGHT, true)
						.commit(),
				)
				manage(action, config)
			}
			val status = query()
			check(if (action == "stop") status == null else status?.ready == true)
			prefs
				.edit()
				.putBoolean(REQUESTED, status != null)
				.remove(IN_FLIGHT)
				.apply()
			report(status)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			report(query())
			Log.e("ClashTproxy", "Native session operation failed", e)
			throw e
		}
	}

	fun enqueue(
		action: String,
		config: JSONObject = JSONObject(),
	) {
		scope.launch {
			try {
				change(action, config)
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				SnackbarController.showMessage(context.getString(R.string.tproxy_failed))
			}
		}
	}

	private fun manage(
		action: String,
		config: JSONObject,
	) {
		val info = context.applicationInfo
		val apks = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
		val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
		val (apk, abi) =
			apks.firstNotNullOf { path ->
				ZipFile(path).use { zip ->
					abis.firstOrNull { zip.getEntry("lib/$it/libclash_android_ffi.so") != null }?.let { path to it }
				}
			}
		val library =
			File(info.nativeLibraryDir, "libclash_android_ffi.so").takeIf { it.isFile }?.path
				?: "$apk!/lib/$abi/libclash_android_ffi.so"
		val runtime = "/system/bin/app_process" + if (Process.is64Bit()) "64" else "32"

		fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
		val uid = Process.myUid()
		val args =
			listOf(
				runtime,
				"/system/bin",
				"--nice-name=clash-tproxy-control",
				TproxyManager::class.java.name,
				action,
				uid.toString(),
				abi,
				library,
				control,
			)
		val command = "export CLASSPATH=${quote(apks.joinToString(":"))}; exec " + args.joinToString(" ", transform = ::quote)
		val profile = File(context.noBackupFilesDir, "tproxy-profile.yaml")
		if (action == "start" || action == "restart") {
			prepareSession(config, profile, uid)
		}
		val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
		try {
			process.outputStream.bufferedWriter().use { it.write(config.toString()) }
			process.inputStream.bufferedReader().useLines { lines -> lines.forEach { _ -> } }
			check(process.waitFor() == 0) { "KernelSU $action failed" }
			// The core has parsed the profile before acknowledging startup.
			profile.delete()
		} finally {
			process.destroy()
		}
	}

	private fun prepareSession(
		config: JSONObject,
		profile: File,
		uid: Int,
	) {
		// Only this app and its native session have the app GID. Permit
		// traversal to the shared sockets without exposing private files.
		val data = File(context.applicationInfo.dataDir)
		val stat = Os.stat(data.path)
		check(stat.st_gid == uid)
		Os.chmod(data.path, (stat.st_mode and 4095) or OsConstants.S_IXGRP)
		for (dir in listOf(context.noBackupFilesDir, context.cacheDir)) {
			val metadata = Os.stat(dir.path)
			check(metadata.st_gid == uid || metadata.st_gid == uid + 10000)
			Os.chmod(dir.path, (metadata.st_mode and 4095) or OsConstants.S_IRWXG)
		}
		// Older VPN sessions created this shared selection cache as 0644.
		val cache = File(context.cacheDir, "cache.db")
		if (cache.isFile) {
			val metadata = Os.lstat(cache.path)
			if (metadata.st_uid == uid && OsConstants.S_ISREG(metadata.st_mode)) {
				Os.chmod(cache.path, (metadata.st_mode and 4095) or OsConstants.S_IWGRP)
			}
		}
		File(config.getString("path")).inputStream().use { copyForCore(it, profile) }
		config.put("path", profile.path)
		for (name in listOf("Country.mmdb", "geosite.dat")) {
			context.assets.open("clash-res/$name").use { copyForCore(it, File(context.cacheDir, name)) }
		}
		val apps = config.getJSONArray("apps")
		val uids =
			(0 until apps.length())
				.mapNotNull {
					try {
						context.packageManager.getApplicationInfo(apps.getString(it), 0).uid
					} catch (_: PackageManager.NameNotFoundException) {
						null
					}
				}.distinct()
		config
			.put("uids", JSONArray(uids))
			.put("workDir", context.cacheDir.path)
			.put("control", control)
			.put("tproxyPort", TproxySocket.PORT)
	}

	private fun copyForCore(
		input: InputStream,
		dest: File,
	) {
		val temp = File.createTempFile("clash-asset", null, dest.parentFile)
		try {
			temp.outputStream().use { input.copyTo(it) }
			Os.chmod(temp.path, 416) // 0640, readable by the app-specific group.
			check(temp.renameTo(dest))
		} finally {
			temp.delete()
		}
	}
}
