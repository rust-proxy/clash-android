package rs.clash.android.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A UI-process mirror of the started VPN service. Observing never starts or keeps it alive. */
internal class ProxySession(
	private val context: Context,
) {
	companion object {
		const val OBSERVE = "rs.clash.android.OBSERVE_SESSION"
		const val SUBSCRIBE = 1
		const val STATE = 2
		const val STOP_FOR_RESTART = 3
	}

	private val snapshot = MutableStateFlow(VpnSessionState())
	val state = snapshot.asStateFlow()

	private var observers = 0
	private var connection: Connection? = null

	suspend fun restart(intent: Intent) {
		val binder = connection?.binder ?: return
		attach()
		try {
			stopAndAwaitExit(binder)
			snapshot.value = VpnSessionState()
			// Bind before starting and keep this observer until the replacement reports
			// running. An Activity going to background can otherwise unbind its last
			// connection while Android is still bringing up the new service.
			reconnect()
			ContextCompat.startForegroundService(context, intent)
			checkNotNull(withTimeoutOrNull(30_000) { state.first { it.running } }) { "VPN service did not report startup" }
		} finally {
			detach()
		}
	}

	private suspend fun stopAndAwaitExit(binder: IBinder) {
		// A stopped state can arrive before the old native sockets and TUN are closed.
		// Wait for this specific process to die, independently of Activity bindings.
		val exited = CompletableDeferred<Unit>()
		val death = IBinder.DeathRecipient { exited.complete(Unit) }
		try {
			binder.linkToDeath(death, 0)
		} catch (_: DeadObjectException) {
			return
		}
		try {
			Messenger(binder).send(Message.obtain(null, STOP_FOR_RESTART))
			checkNotNull(withTimeoutOrNull(30_000) { exited.await() }) { "VPN service did not exit" }
		} catch (_: DeadObjectException) {
			// It already exited between registration and the stop request.
		} finally {
			binder.unlinkToDeath(death, 0)
		}
	}

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
		var binder: IBinder? = null
			private set

		override fun onServiceConnected(
			name: ComponentName,
			service: IBinder,
		) {
			if (connection !== this) return
			binder = service
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
