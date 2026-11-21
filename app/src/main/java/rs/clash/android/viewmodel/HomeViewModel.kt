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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rs.clash.android.R
import rs.clash.android.data.AppData
import rs.clash.android.data.Overview
import rs.clash.android.data.OverviewRepository
import rs.clash.android.service.VpnController
import rs.clash.android.service.VpnStartResult
import rs.clash.android.service.coreReadyChanges
import rs.clash.android.service.runningChanges

data class HomeUiState(
	val isVpnRunning: Boolean = false,
	val overview: Overview = Overview(),
	val hasLoadedOverview: Boolean = false,
)

sealed interface HomeAction {
	data object StartVpn : HomeAction

	data object RestartVpn : HomeAction

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
}

class HomeViewModel(
	private val overviewRepository: OverviewRepository = AppData.core,
	private val vpn: VpnController = AppData.vpn,
) : ViewModel() {
	private val state = MutableStateFlow(HomeUiState(isVpnRunning = vpn.session.value.running))
	val uiState = state.asStateFlow()
	private val effectChannel = Channel<HomeEffect>(Channel.BUFFERED)
	private var restartAfterPermission = false
	val effects = effectChannel.receiveAsFlow()

	init {
		// Keep service state current while hidden without polling the controller.
		viewModelScope.launch {
			vpn.runningChanges.collect { updateRunningState(it) }
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
			HomeAction.RestartVpn -> startVpn(restart = true)
			HomeAction.StopVpn -> vpn.stop()
			is HomeAction.PermissionResult -> {
				val restart = restartAfterPermission && vpn.session.value.running
				restartAfterPermission = false
				if (action.granted) startVpn(restart)
			}
		}
	}

	private fun startVpn(restart: Boolean = false) {
		if (restart) {
			viewModelScope.launch {
				try {
					handleStartResult(vpn.restart(), restart = true)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					effectChannel.send(HomeEffect.Message(R.string.home_restart_failed, e.localizedMessage ?: e.toString()))
				}
			}
		} else {
			handleStartResult(vpn.start(), restart = false)
		}
	}

	private fun handleStartResult(
		result: VpnStartResult,
		restart: Boolean,
	) {
		when (result) {
			VpnStartResult.Started -> Unit
			VpnStartResult.MissingProfile -> effectChannel.trySend(HomeEffect.Message(R.string.home_select_profile_first))
			is VpnStartResult.PermissionRequired -> {
				restartAfterPermission = restart
				effectChannel.trySend(HomeEffect.RequestVpnPermission(result.intent))
			}
		}
	}

	/** The resumed destination owns this collection, including in-flight FFI requests. */
	suspend fun observe() {
		pollWhileServiceRunning(
			vpn.coreReadyChanges,
			intervalMillis = 3000,
			initialDelayMillis = { if (state.value.hasLoadedOverview) 0L else 1000L },
			refresh = ::refreshOverview,
		)
	}

	private suspend fun refreshOverview() {
		try {
			val overview = overviewRepository.getOverview()
			currentCoroutineContext().ensureActive()
			if (vpn.session.value.running) state.update { it.copy(overview = overview, hasLoadedOverview = true) }
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			currentCoroutineContext().ensureActive()
			if (vpn.session.value.running) {
				effectChannel.send(HomeEffect.Message(R.string.home_stats_error, e.message ?: e.toString()))
			}
		}
	}
}
