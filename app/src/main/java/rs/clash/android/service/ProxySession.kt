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
import rs.clash.android.diagnostics.ProxyDiagnostics

/** A UI-process mirror of the started proxy service. Observing never starts or keeps it alive. */
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
	private val connections = mutableListOf<Connection>()

	private fun publish() {
		snapshot.value = connections.firstOrNull { it.report.running }?.report ?: VpnSessionState()
	}

	fun stop() {
		val active = connections.firstOrNull { it.report.running } ?: return
		context.startService(Intent(context, active.type).setAction(ProxyServiceSession.STOP))
	}

	suspend fun restart(intent: Intent) {
		val binder = connections.firstOrNull { it.report.running }?.binder ?: return
		stopAndAwaitExit(binder)
		snapshot.value = VpnSessionState()
		reconnect()
		ContextCompat.startForegroundService(context, intent)
		// Binder death can arrive before ActivityManager removes the old process record.
		// Redeliver an unacknowledged start once. The service ignores it if already started.
		val ready =
			withTimeoutOrNull(30_000) {
				if (withTimeoutOrNull(3_000) { state.first { it.running } } == null) {
					ContextCompat.startForegroundService(context, intent)
					state.first { it.running }
				}
				state.first { it.coreReady || !it.running }.coreReady
			}
		check(ready == true) { "Proxy service did not report startup" }
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
			checkNotNull(withTimeoutOrNull(30_000) { exited.await() }) { "Proxy service did not exit" }
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
		connections.forEach(context::unbindService)
		connections.clear()
		for (type in listOf(TunService::class.java, KernelSuService::class.java)) {
			val client = Connection(type)
			connections.add(client)
			context.bindService(Intent(context, type).setAction(OBSERVE), client, 0)
		}
	}

	fun detach() {
		if (--observers != 0) return
		connections.forEach(context::unbindService)
		connections.clear()
		snapshot.value = VpnSessionState()
	}

	private inner class Connection(
		val type: Class<*>,
	) : ServiceConnection {
		var report = VpnSessionState()
		var binder: IBinder? = null
			private set

		override fun onServiceConnected(
			name: ComponentName,
			service: IBinder,
		) {
			if (this !in connections) return
			binder = service
			val handler =
				Handler(Looper.getMainLooper()) { message ->
					if (this in connections && message.what == STATE) {
						val running = message.arg1 != 0
						report = VpnSessionState(running, message.arg2.takeIf { running && it > 0 }, message.arg1 == ProxyServiceSession.CORE_READY)
						publish()
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
			if (this !in connections) return
			ProxyDiagnostics.recover(context, afterDisconnect = true)
			report = VpnSessionState()
			publish()
			reconnect()
		}

		override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
	}
}
