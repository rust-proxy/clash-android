package rs.clash.android.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rs.clash.android.Global
import uniffi.clash_android_ffi.ClashController
import uniffi.clash_android_ffi.Connection

class ConnectionsViewModel : ViewModel() {
	private val controller by lazy { ClashController("${Global.application.cacheDir}/clash.sock") }
	private var pollingJob: Job? = null

	var connections by mutableStateOf<List<Connection>>(emptyList())
		private set

	var downloadTotal by mutableLongStateOf(0)
		private set

	var uploadTotal by mutableLongStateOf(0)
		private set

	var isRefreshing by mutableStateOf(false)
		private set

	var errorMessage by mutableStateOf<String?>(null)
		private set

	init {
		startPolling()
	}

	private fun startPolling() {
		pollingJob = viewModelScope.launch(Dispatchers.Default) {
			while (isActive) {
				// The controller talks to clash's unix socket, so there is nothing to
				// poll (and nothing to report) while the VPN is down.
				if (Global.isServiceRunning.value) {
					fetchConnections()
				}
				delay(2000)
			}
		}
	}

	override fun onCleared() {
		super.onCleared()
		pollingJob?.cancel()
	}

	fun fetchConnections() {
		isRefreshing = true
		errorMessage = null
		viewModelScope.launch {
			try {
				val response = controller.getConnections()
				connections = response.connections
				downloadTotal = response.downloadTotal
				uploadTotal = response.uploadTotal
			} catch (e: Exception) {
				Log.e("ConnectionsAPI", "Failed to fetch connections", e)
				errorMessage = "Failed to load connections: ${e.message}"
				connections = emptyList()
			} finally {
				isRefreshing = false
			}
		}
	}
}
