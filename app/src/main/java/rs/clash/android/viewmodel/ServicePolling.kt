package rs.clash.android.viewmodel

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

/** Runs in the caller's lifecycle scope; stopping the VPN cancels any in-flight request. */
internal suspend fun pollWhileServiceRunning(
	running: StateFlow<Boolean>,
	intervalMillis: Long,
	initialDelayMillis: Long = 0,
	refresh: suspend () -> Unit,
) {
	running.collectLatest { enabled ->
		if (enabled) {
			delay(initialDelayMillis)
			while (currentCoroutineContext().isActive) {
				refresh()
				delay(intervalMillis)
			}
		}
	}
}
