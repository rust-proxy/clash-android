package rs.clash.android.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.clash.android.R
import rs.clash.android.data.AppData
import rs.clash.android.data.Overview
import rs.clash.android.data.OverviewRepository
import rs.clash.android.service.AndroidVpnController
import rs.clash.android.service.VpnController
import rs.clash.android.service.VpnStartResult

data class HomeUiState(
	val isVpnRunning: Boolean = false,
	val overview: Overview = Overview(),
	val hasLoadedOverview: Boolean = false,
)

sealed interface HomeAction {
	data object StartVpn : HomeAction

	data object StopVpn : HomeAction

	data class PermissionResult(
		val granted: Boolean,
	) : HomeAction
}

sealed interface HomeEffect {
	data class RequestVpnPermission(
		val intent: Intent,
	) : HomeEffect

	data class Message(
		val resource: Int,
		val detail: String? = null,
	) : HomeEffect

	data object RestartApp : HomeEffect
}

class HomeViewModel(
	private val overviewRepository: OverviewRepository = AppData.core,
	private val vpn: VpnController = AndroidVpnController(),
) : ViewModel() {
	private val state = MutableStateFlow(HomeUiState(isVpnRunning = vpn.running.value))
	val uiState = state.asStateFlow()
	private val effectChannel = Channel<HomeEffect>(Channel.BUFFERED)
	val effects = effectChannel.receiveAsFlow()

	init {
		// Keep service state current while hidden without polling the controller.
		viewModelScope.launch {
			vpn.running.collect { updateRunningState(it) }
		}
	}

	private fun updateRunningState(running: Boolean) {
		state.update { current ->
			if (current.isVpnRunning == running) current else HomeUiState(isVpnRunning = running)
		}
	}

	fun onAction(action: HomeAction) {
		when (action) {
			HomeAction.StartVpn -> startVpn()
			HomeAction.StopVpn -> {
				vpn.stop()
				effectChannel.trySend(HomeEffect.RestartApp)
			}
			is HomeAction.PermissionResult -> if (action.granted) startVpn()
		}
	}

	private fun startVpn() {
		when (val result = vpn.start()) {
			VpnStartResult.Started -> Unit
			VpnStartResult.MissingProfile -> effectChannel.trySend(HomeEffect.Message(R.string.home_select_profile_first))
			is VpnStartResult.PermissionRequired -> effectChannel.trySend(HomeEffect.RequestVpnPermission(result.intent))
		}
	}

	/** The resumed destination owns this collection, including in-flight FFI requests. */
	suspend fun observe() {
		vpn.running.collectLatest { running ->
			updateRunningState(running)
			if (running) {
				// Only a new VPN session needs the controller startup grace period.
				val initialDelay = if (state.value.hasLoadedOverview) 0L else 1000L
				pollWhileServiceRunning(vpn.running, 3000, initialDelay, ::refreshOverview)
			}
		}
	}

	private suspend fun refreshOverview() {
		try {
			val overview = overviewRepository.getOverview()
			currentCoroutineContext().ensureActive()
			if (vpn.running.value) state.update { it.copy(overview = overview, hasLoadedOverview = true) }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			currentCoroutineContext().ensureActive()
			if (vpn.running.value) {
				effectChannel.send(HomeEffect.Message(R.string.home_stats_error, e.message ?: e.toString()))
			}
		}
	}
}
