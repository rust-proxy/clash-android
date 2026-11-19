package rs.clash.android.viewmodel

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

/** Runs in the caller's lifecycle scope; stopping the VPN cancels any in-flight request. */
internal suspend fun pollWhileServiceRunning(
	running: Flow<Boolean>,
	intervalMillis: Long,
	initialDelayMillis: () -> Long = { 0L },
	refresh: suspend () -> Unit,
) {
	running.collectLatest { enabled ->
		if (enabled) {
			delay(initialDelayMillis())
			while (currentCoroutineContext().isActive) {
				refresh()
				delay(intervalMillis)
			}
		}
	}
}
