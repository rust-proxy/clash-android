package rs.clash.android.viewmodel

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rs.clash.android.R
import rs.clash.android.model.Profile
import rs.clash.android.model.ProfileType
import uniffi.clash_android_ffi.EyreException
import uniffi.clash_android_ffi.Peer
import uniffi.clash_android_ffi.TransferEvent
import uniffi.clash_android_ffi.TransferException
import uniffi.clash_android_ffi.TransferSession
import uniffi.clash_android_ffi.openTransfer
import java.io.File
import java.net.Inet4Address

internal data class TransferState(
	val profile: Profile?,
	val address: String = "",
	val peers: List<Peer> = emptyList(),
	val offer: TransferEvent.Offer? = null,
	val busy: Boolean = false,
	val finished: Boolean = false,
	val message: String = "",
)

internal class ProfileTransfer(
	private val app: Application,
	private val scope: CoroutineScope,
	private val importProfile: suspend (File, String, String?) -> String,
) {
	var state by mutableStateOf<TransferState?>(null)
		private set
	private var job: Job? = null
	private var session: TransferSession? = null
	private var actions: CoroutineScope? = null
	private var accepted: String? = null
	private var receiveTimeout: Job? = null

	fun open(profile: Profile?) {
		close()
		val previous = job
		state = TransferState(profile)
		job =
			scope.launch {
				previous?.join()
				try {
					runSession(profile)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					val message =
						when (e) {
							is EyreException -> R.string.lan_invalid
							is TransferException.Subscription -> R.string.lan_invalid_subscription
							else -> R.string.lan_failed
						}
					state = state?.copy(finished = true, busy = false, offer = null, message = app.getString(message))
				}
			}
	}

	fun close() {
		session?.stop()
		job?.cancel()
		state = null
	}

	private suspend fun runSession(profile: Profile?) {
		val manager = app.getSystemService(ConnectivityManager::class.java)
		// Select the physical LAN even when the default network is a VPN.
		val network =
			manager.allNetworks.firstOrNull { network ->
				val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
				caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
					(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
					manager.getLinkProperties(network)?.linkAddresses?.any { it.address is Inet4Address } == true
			} ?: throw IllegalStateException()
		val address = manager.getLinkProperties(network)!!.linkAddresses.first { it.address is Inet4Address }
		val host = requireNotNull(address.address.hostAddress)
		val lock =
			if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
				app.getSystemService(WifiManager::class.java).createMulticastLock("LocalSend")
			} else {
				null
			}
		val scratch = if (profile == null) File.createTempFile("localsend-", ".yaml", app.cacheDir) else null
		try {
			lock?.acquire()
			val native = openTransfer("Clash · ${Build.MODEL}", "$host/${address.prefixLength}", scratch?.absolutePath)
			try {
				session = native
				state = state?.copy(address = "$host:${native.port()}")
				coroutineScope {
					actions = this
					launch { native.announce() }
					while (true) {
						when (val event = native.nextEvent()) {
							TransferEvent.PeersChanged -> state = state?.copy(peers = native.peers().sortedBy { it.alias })
							is TransferEvent.Offer -> state = state?.copy(offer = event, message = "")
							is TransferEvent.Withdrawn ->
								if (accepted == event.id || state?.offer?.id == event.id) {
									receiveTimeout?.cancel()
									accepted = null
									state = state?.copy(offer = null, busy = false, message = app.getString(R.string.lan_cancelled))
								}
							is TransferEvent.Received -> {
								receiveTimeout?.cancel()
								val name = importProfile(checkNotNull(scratch), event.name, event.url)
								val message = if (event.url == null) R.string.lan_imported else R.string.lan_imported_remote
								state = state?.copy(offer = null, busy = false, finished = true, message = app.getString(message, name))
								break
							}
						}
					}
				}
			} finally {
				actions = null
				accepted = null
				session = null
				receiveTimeout?.cancel()
				native.stop()
				try {
					withContext(NonCancellable) { native.waitStopped() }
				} finally {
					native.close()
				}
			}
		} finally {
			if (lock?.isHeld == true) lock.release()
			scratch?.delete()
		}
	}

	fun answer(accept: Boolean) {
		val current = state ?: return
		val offer = current.offer ?: return
		session?.answer(offer.id, accept)
		accepted = if (accept) offer.id else null
		state = current.copy(offer = null, busy = accept)
		if (accept) {
			receiveTimeout =
				actions?.launch {
					kotlinx.coroutines.delay(120_000)
					state = state?.copy(busy = false, finished = true, message = app.getString(R.string.lan_failed))
					job?.cancel()
				}
		}
	}

	fun find(address: String) =
		action { native ->
			val parts = address.trim().split(':')
			require(parts.size <= 2)
			native.findPeer(parts.first(), parts.getOrNull(1)?.toUShort() ?: 53317u)
		}

	fun send(
		peer: Peer,
		pin: String,
	) = action { native ->
		val profile = checkNotNull(state?.profile)
		val base =
			profile.name
				.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
				.take(120)
				.trim('.', ' ')
				.ifEmpty { "profile" }
		val filename = if (base.endsWith(".yaml", true) || base.endsWith(".yml", true)) base else "$base.yaml"
		val url = if (profile.type == ProfileType.REMOTE) profile.url.orEmpty() else null
		native.send(peer.fingerprint, profile.filePath, filename, pin.ifBlank { null }, url)
		state = state?.copy(finished = true, message = app.getString(R.string.lan_sent))
		job?.cancel()
	}

	private fun action(block: suspend (TransferSession) -> Unit) {
		val native = session ?: return
		if (state?.busy != false || state?.finished == true) return
		state = state?.copy(busy = true, message = "")
		actions?.launch {
			try {
				block(native)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				val reason =
					when (e) {
						is TransferException.Pin -> R.string.lan_pin_required
						is TransferException.Declined -> R.string.lan_declined
						is TransferException.File -> R.string.lan_size
						is TransferException.Subscription -> R.string.lan_invalid_subscription
						else -> R.string.lan_failed
					}
				state = state?.copy(message = app.getString(reason))
			} finally {
				state = state?.copy(busy = false)
			}
		}
	}
}
