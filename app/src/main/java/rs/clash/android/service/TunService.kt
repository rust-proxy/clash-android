package rs.clash.android.service

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rs.clash.android.util.NotificationHelper
import uniffi.clash_android_ffi.ClashInstance
import uniffi.clash_android_ffi.ProfileOverride
import uniffi.clash_android_ffi.runClash
import java.io.File

@SuppressLint("VpnServicePolicy")
class TunService : VpnService() {
	companion object {
		internal const val CONFIG = "session_config"
		internal const val STOP = "rs.clash.android.STOP_VPN"
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private var started = false
	private var instance: ClashInstance? = null
	private var observer: Messenger? = null
	private val control =
		Messenger(
			Handler(Looper.getMainLooper()) { message ->
				if (message.sendingUid == Process.myUid()) {
					when (message.what) {
						ProxySession.SUBSCRIBE -> {
							observer = message.replyTo
							sendState()
						}
						ProxySession.STOP_FOR_RESTART -> stopSession()
					}
				}
				true
			},
		)

	override fun onBind(intent: Intent): IBinder? = if (intent.action == ProxySession.OBSERVE) control.binder else super.onBind(intent)

	override fun onUnbind(intent: Intent): Boolean {
		if (intent.action == ProxySession.OBSERVE) observer = null
		return super.onUnbind(intent)
	}

	private fun sendState() {
		try {
			observer?.send(Message.obtain(null, ProxySession.STATE, if (started) 1 else 0, instance?.mixedPort()?.toInt() ?: 0))
		} catch (_: RemoteException) {
			observer = null
		}
	}

	override fun onStartCommand(
		intent: Intent?,
		flags: Int,
		startId: Int,
	): Int {
		if (intent?.action == STOP) {
			stopSession()
			return START_NOT_STICKY
		}
		if (started) return START_STICKY
		started = true
		try {
			val notification = NotificationHelper.createNotification(this)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startForeground(NotificationHelper.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
			} else {
				startForeground(NotificationHelper.NOTIFICATION_ID, notification)
			}
		} catch (e: RuntimeException) {
			Log.e("clash", "Could not start foreground VPN service", e)
			stopSession()
			return START_NOT_STICKY
		}
		sendState()
		scope.launch {
			try {
				val config =
					withContext(Dispatchers.IO) {
						// Only :proxy reads/writes this snapshot, including on a sticky or always-on restart.
						val prefs = getSharedPreferences("proxy_session", MODE_PRIVATE)
						val supplied = intent?.getStringExtra(CONFIG)
						val saved = supplied ?: prefs.getString(CONFIG, null) ?: error("No saved VPN configuration")
						val settings = JSONObject(saved)
						if (supplied != null) check(prefs.edit().putString(CONFIG, supplied).commit())
						for (name in listOf("Country.mmdb", "geosite.dat")) {
							assets.open("clash-res/$name").use { input ->
								File(cacheDir, name).outputStream().use { output -> input.copyTo(output) }
							}
						}
						settings
					}
				runSession(config)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.e("clash", "VPN session failed", e)
			}
			stopSession()
		}
		return START_STICKY
	}

	private suspend fun runSession(config: JSONObject) {
		val builder =
			Builder()
				.setSession("ClashRS VPNService")
				.addAddress("10.0.0.1", 30)
				.addRoute("0.0.0.0", 0)
				.addDnsServer("10.0.0.2")
				.allowBypass()
		val allowed = config.getString("mode") == "ALLOWED"
		if (!allowed) builder.addDisallowedApplication(packageName)
		val apps = config.getJSONArray("apps")
		for (index in 0 until apps.length()) {
			try {
				val app = apps.getString(index)
				if (allowed) builder.addAllowedApplication(app) else builder.addDisallowedApplication(app)
			} catch (_: PackageManager.NameNotFoundException) {
				// A selected app may have been uninstalled since the session was saved.
			}
		}

		val path = config.getString("path")
		val fakeIp = config.getBoolean("fakeIp")
		val ipv6 = config.getBoolean("ipv6")
		val fd = checkNotNull(builder.establish()) { "VPN permission revoked" }.use { it.detachFd() }
		// clash-rs takes ownership through AsyncDevice::from_fd. Reclaim the fd here only
		// when runClash fails before spawning the native task.
		val clash =
			try {
				runClash(path, cacheDir.path, ProfileOverride(fd, fakeIp = fakeIp, ipv6 = ipv6))
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				ParcelFileDescriptor.adoptFd(fd).close()
				throw e
			}
		instance = clash
		sendState()
		clash.awaitStopped()
	}

	override fun onRevoke() {
		Handler(Looper.getMainLooper()).post { stopSession() }
	}

	private fun stopSession() {
		stopSelf()
		if (started) releaseProxy()
	}

	override fun onDestroy() {
		super.onDestroy()
		if (started) releaseProxy()
	}

	private fun releaseProxy() {
		started = false
		val clash = instance
		instance = null
		try {
			sendState()
			scope.cancel()
			clash?.shutdown()
		} finally {
			// Some core tasks outlive shutdown. End only the dedicated proxy process so
			// its descriptors and native runtime are gone before the next VPN session.
			Process.killProcess(Process.myPid())
		}
	}
}
