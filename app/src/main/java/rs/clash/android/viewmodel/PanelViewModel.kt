package rs.clash.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rs.clash.android.Global
import rs.clash.android.R
import rs.clash.android.data.AppData
import rs.clash.android.data.ProxyInfo
import rs.clash.android.data.ProxyRepository

sealed interface ProxyDelay {
	data object Testing : ProxyDelay

	data object Timeout : ProxyDelay

	data class Measured(
		val millis: Int,
	) : ProxyDelay
}

data class PanelUiState(
	val proxies: List<ProxyInfo> = emptyList(),
	val delays: Map<String, ProxyDelay> = emptyMap(),
	val isRefreshing: Boolean = false,
	val isVpnRunning: Boolean = false,
	val hasLoaded: Boolean = false,
) {
	val isLoading: Boolean get() = isRefreshing || (isVpnRunning && !hasLoaded)
	val groups: List<ProxyInfo> get() = proxies.filter { it.all.isNotEmpty() }
	val proxyTypes: Map<String, String> get() = proxies.associate { it.name to it.proxyType }
}

sealed interface PanelAction {
	data object Refresh : PanelAction

	data class SelectProxy(
		val groupName: String,
		val proxyName: String,
	) : PanelAction

	data class TestGroupDelay(
		val groupName: String,
	) : PanelAction
}

data class PanelMessage(
	val resource: Int,
	val detail: String,
)

class PanelViewModel(
	private val repository: ProxyRepository = AppData.core,
	private val running: StateFlow<Boolean> = Global.isServiceRunning,
) : ViewModel() {
	private val state = MutableStateFlow(PanelUiState(isVpnRunning = running.value))
	val uiState = state.asStateFlow()
	private val actions = MutableSharedFlow<PanelAction>(extraBufferCapacity = 64)
	private val messages = Channel<PanelMessage>(Channel.BUFFERED)
	val effects = messages.receiveAsFlow()
	private val refreshMutex = Mutex()
	private val testing = mutableSetOf<String>()

	init {
		// Invalidate cached data across VPN sessions, even while this tab is hidden.
		// This observer does not perform controller requests.
		viewModelScope.launch {
			running.collect { enabled ->
				updateRunningState(enabled)
			}
		}
	}

	private fun updateRunningState(enabled: Boolean) {
		state.update { current ->
			if (current.isVpnRunning == enabled) current else PanelUiState(isVpnRunning = enabled)
		}
	}

	fun onAction(action: PanelAction) {
		actions.tryEmit(action)
	}

	/** Hidden/stopped pages have no action subscriber and cancel all outstanding work. */
	suspend fun observe() {
		running.collectLatest { enabled ->
			updateRunningState(enabled)
			if (enabled) {
				coroutineScope {
					launch(start = CoroutineStart.UNDISPATCHED) {
						actions.collect { action -> launch { handleAction(action) } }
					}
					refreshProxies(retryOnFailure = !state.value.hasLoaded)
				}
			}
		}
	}

	private suspend fun handleAction(action: PanelAction) {
		when (action) {
			PanelAction.Refresh -> refreshProxies()
			is PanelAction.SelectProxy -> {
				refreshMutex.withLock {
					try {
						repository.selectProxy(action.groupName, action.proxyName)
						currentCoroutineContext().ensureActive()
						if (running.value) loadProxies()
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						reportError(R.string.home_select_proxy_failed, e)
					}
				}
			}
			is PanelAction.TestGroupDelay -> {
				val proxies = state.value.proxies
				val group = proxies.firstOrNull { it.name == action.groupName } ?: return
				val leaves = proxies.filter { it.all.isEmpty() }.map { it.name }.toSet()
				coroutineScope {
					group.all
						.filter { it in leaves }
						.distinct()
						.map { name -> async { testDelay(name) } }
						.awaitAll()
				}
			}
		}
	}

	private suspend fun refreshProxies(retryOnFailure: Boolean = false) {
		refreshMutex.withLock { loadProxies(retryOnFailure) }
	}

	private suspend fun loadProxies(retryOnFailure: Boolean = false) {
		if (!running.value) return
		state.update { it.copy(isRefreshing = true) }
		try {
			val proxies =
				try {
					repository.getProxies()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					if (!retryOnFailure) throw e
					// The service can announce startup before its controller socket is ready.
					// A ready controller never pays this delay when returning to the tab.
					delay(1000)
					repository.getProxies()
				}
			currentCoroutineContext().ensureActive()
			if (!running.value) return
			state.update { current ->
				val delays =
					proxies
						.mapNotNull { proxy ->
							val previous = current.delays[proxy.name]
							val measured = proxy.lastDelay?.takeIf { it > 0 }?.let(ProxyDelay::Measured)
							val value = if (previous == ProxyDelay.Testing) previous else measured ?: previous
							value?.let { proxy.name to it }
						}.toMap()
				current.copy(proxies = proxies, delays = delays, hasLoaded = true)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			currentCoroutineContext().ensureActive()
			if (running.value) state.update { it.copy(hasLoaded = true) }
			reportError(R.string.home_api_error, e)
		} finally {
			state.update { it.copy(isRefreshing = false) }
		}
	}

	private suspend fun testDelay(name: String) {
		if (!testing.add(name)) return
		val previous = state.value.delays[name]
		try {
			state.update { it.copy(delays = it.delays + (name to ProxyDelay.Testing)) }
			val delay = repository.getProxyDelay(name)
			currentCoroutineContext().ensureActive()
			if (running.value) state.update { it.copy(delays = it.delays + (name to ProxyDelay.Measured(delay))) }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			currentCoroutineContext().ensureActive()
			if (running.value) state.update { it.copy(delays = it.delays + (name to ProxyDelay.Timeout)) }
		} finally {
			testing.remove(name)
			state.update { current ->
				if (current.delays[name] == ProxyDelay.Testing) {
					val delays = if (previous == null) current.delays - name else current.delays + (name to previous)
					current.copy(delays = delays)
				} else {
					current
				}
			}
		}
	}

	private suspend fun reportError(
		resource: Int,
		error: Exception,
	) {
		currentCoroutineContext().ensureActive()
		if (running.value) messages.send(PanelMessage(resource, error.message ?: error.toString()))
	}
}
