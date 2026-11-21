package rs.clash.android.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rs.clash.android.diagnostics.ProxyDiagnostics
import rs.clash.android.util.NotificationHelper
import java.io.File

internal class ProxyServiceSession(
	private val service: Service,
	private val run: suspend (JSONObject, (Int) -> Unit) -> Unit,
) {
	companion object {
		const val CONFIG = "session_config"
		const val STOP = "rs.clash.android.STOP_VPN"
		const val CORE_READY = 2
	}

	private var job: Job? = null
	private var status = 0
	private var port = 0
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
						ProxySession.STOP_FOR_RESTART -> stop()
					}
				}
				true
			},
		)

	fun bind(intent: Intent): IBinder? = if (intent.action == ProxySession.OBSERVE) control.binder else null

	fun unbind(intent: Intent) {
		if (intent.action == ProxySession.OBSERVE) observer = null
	}

	private fun sendState() {
		try {
			observer?.send(Message.obtain(null, ProxySession.STATE, status, port))
		} catch (_: RemoteException) {
			observer = null
		}
	}

	fun start(intent: Intent?): Int {
		if (intent?.action == STOP) {
			stop()
			return Service.START_NOT_STICKY
		}
		val policy = Service.START_STICKY
		if (job != null) return policy
		try {
			val notification = NotificationHelper.createNotification(service)
			if (Build.VERSION.SDK_INT >= 34) {
				service.startForeground(NotificationHelper.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
			} else {
				service.startForeground(NotificationHelper.NOTIFICATION_ID, notification)
			}
		} catch (e: RuntimeException) {
			Log.e("clash", "Could not start foreground service", e)
			service.stopSelf()
			return Service.START_NOT_STICKY
		}
		status = 1
		sendState()
		job =
			CoroutineScope(Dispatchers.Main).launch(start = CoroutineStart.UNDISPATCHED) {
				try {
					val config =
						withContext(Dispatchers.IO) {
							val supplied = intent?.getStringExtra(CONFIG)
							// Only VPN sessions can be restored by Android (including always-on).
							val prefs = service.getSharedPreferences("proxy_session", Service.MODE_PRIVATE)
							val saved = supplied ?: prefs.getString(CONFIG, null)
							val settings = JSONObject(checkNotNull(saved))
							check(!settings.optBoolean(NativeTproxy.MODE_KEY))
							if (supplied != null) check(prefs.edit().putString(CONFIG, supplied).commit())
							for (name in listOf("Country.mmdb", "geosite.dat")) {
								service.assets.open("clash-res/$name").use { input ->
									File(service.cacheDir, name).outputStream().use { input.copyTo(it) }
								}
							}
							settings
						}
					run(config) {
						status = CORE_READY
						port = it
						sendState()
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Log.e("clash", "Proxy startup or session failed", e)
				} finally {
					status = 0
					port = 0
					sendState()
					service.stopSelf()
					ProxyDiagnostics.markStopped()
					Process.killProcess(Process.myPid())
				}
			}
		return policy
	}

	fun stop() {
		job?.cancel()
		service.stopSelf()
	}
}
