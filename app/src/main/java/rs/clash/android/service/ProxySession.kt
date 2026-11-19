package rs.clash.android.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A UI-process mirror of the started VPN service. Observing never starts or keeps it alive. */
internal class ProxySession(
	private val context: Context,
) {
	companion object {
		const val OBSERVE = "rs.clash.android.OBSERVE_SESSION"
		const val SUBSCRIBE = 1
		const val STATE = 2
	}

	private val snapshot = MutableStateFlow(VpnSessionState())
	val state = snapshot.asStateFlow()

	private var observers = 0
	private var connection: Connection? = null

	fun attach() {
		if (++observers != 1) return
		reconnect()
	}

	private fun reconnect() {
		if (observers == 0) return
		connection?.let(context::unbindService)
		val client = Connection()
		connection = client
		val intent = Intent(context, TunService::class.java).setAction(OBSERVE)
		// Binding without BIND_AUTO_CREATE also observes subsequent starts.
		context.bindService(intent, client, 0)
	}

	fun detach() {
		if (--observers != 0) return
		connection?.let(context::unbindService)
		connection = null
		snapshot.value = VpnSessionState()
	}

	private inner class Connection : ServiceConnection {
		override fun onServiceConnected(
			name: ComponentName,
			service: IBinder,
		) {
			if (connection !== this) return
			val handler =
				Handler(Looper.getMainLooper()) { message ->
					if (connection === this && message.what == STATE) {
						val running = message.arg1 != 0
						snapshot.value = VpnSessionState(running, message.arg2.takeIf { running && it > 0 })
					}
					true
				}
			try {
				Messenger(service).send(Message.obtain(null, SUBSCRIBE).apply { replyTo = Messenger(handler) })
			} catch (_: RemoteException) {
				onServiceDisconnected(name)
			}
		}

		override fun onServiceDisconnected(name: ComponentName) {
			if (connection !== this) return
			snapshot.value = VpnSessionState()
			reconnect()
		}

		override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
	}
}
