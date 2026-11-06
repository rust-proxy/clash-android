package rs.clash.android.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import rs.clash.android.Global
import rs.clash.android.data.AppData
import rs.clash.android.data.ConnectionsRepository
import uniffi.clash_android_ffi.Connection

data class ConnectionsUiState(
	val connections: List<Connection> = emptyList(),
	val downloadTotal: Long = 0,
	val uploadTotal: Long = 0,
	val isRefreshing: Boolean = false,
	val errorMessage: String? = null,
)

class ConnectionsViewModel(
	private val repository: ConnectionsRepository = AppData.core,
	private val running: StateFlow<Boolean> = Global.isServiceRunning,
) : ViewModel() {
	private val state = MutableStateFlow(ConnectionsUiState())
	val uiState = state.asStateFlow()

	/** The destination lifecycle owns polling and cancels in-flight requests when hidden. */
	suspend fun observe() {
		try {
			running.collectLatest { enabled ->
				state.value = ConnectionsUiState()
				if (enabled) pollWhileServiceRunning(running, 2000, refresh = ::fetchConnections)
			}
		} finally {
			// A retained navigation entry should not retain the entire connection snapshot.
			state.value = ConnectionsUiState()
		}
	}

	private suspend fun fetchConnections() {
		state.update { it.copy(isRefreshing = true, errorMessage = null) }
		try {
			val response = repository.getConnections()
			currentCoroutineContext().ensureActive()
			if (!running.value) return
			state.value =
				ConnectionsUiState(
					connections = response.connections,
					downloadTotal = response.downloadTotal,
					uploadTotal = response.uploadTotal,
				)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			currentCoroutineContext().ensureActive()
			if (!running.value) return
			state.update { it.copy(connections = emptyList(), errorMessage = e.message ?: e.toString()) }
		} finally {
			state.update { it.copy(isRefreshing = false) }
		}
	}
}
