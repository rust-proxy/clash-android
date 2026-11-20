package rs.clash.android.viewmodel

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import rs.clash.android.BuildConfig
import rs.clash.android.R
import rs.clash.android.update.AppUpdate
import rs.clash.android.update.GitHubUpdates
import rs.clash.android.update.InvalidUpdate
import rs.clash.android.update.NativeTlsUnavailable
import rs.clash.android.update.UnsupportedAbi
import rs.clash.android.update.UpdateChannel
import rs.clash.android.update.UpdateManifest
import rs.clash.android.update.selectUpdate
import java.io.File

internal sealed interface UpdateStatus {
	data object Idle : UpdateStatus

	data object Checking : UpdateStatus

	data object Current : UpdateStatus

	data class Available(
		val update: AppUpdate,
	) : UpdateStatus

	data class Downloading(
		val percent: Int,
	) : UpdateStatus

	data class Ready(
		val update: AppUpdate,
		val file: File,
	) : UpdateStatus

	data class Failed(
		val message: Int,
	) : UpdateStatus
}

internal class AppUpdateViewModel(
	application: Application,
	private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {
	private val prefs = application.getSharedPreferences("app-updates", 0)
	private val github = GitHubUpdates(application)
	private var job: Job? = null
	private var promptWhenAvailable = false
	var channel by mutableStateOf(
		UpdateChannel.entries.firstOrNull { it.id == prefs.getString("channel", null) }
			?: if (BuildConfig.TESTING_CHANNEL) UpdateChannel.TESTING else UpdateChannel.STABLE,
	)
		private set
	var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
		private set
	var visible by mutableStateOf(savedState["visible"] ?: false)
		private set

	init {
		// Android may recreate the activity/process while the user grants installation access.
		val saved = savedState.get<String>("verified")
		if (saved != null) {
			runOperation(R.string.update_invalid) {
				val update =
					selectUpdate(saved, channel, application.packageName, BuildConfig.VERSION_CODE.toLong(), Build.SUPPORTED_ABIS.toList())
				if (update == null) {
					status = UpdateStatus.Current
				} else {
					val cached = github.verifiedCache(update)
					status = if (cached == null) UpdateStatus.Available(update) else UpdateStatus.Ready(update, cached)
				}
			}
		} else if (visible) {
			check()
		}
	}

	fun onForeground() {
		if (visible || job?.isActive == true || status is UpdateStatus.Ready) return
		val elapsed = System.currentTimeMillis() - prefs.getLong("last-${channel.id}", 0)
		if (elapsed in 0 until 4 * 60 * 60 * 1000L) return
		promptWhenAvailable = true
		check()
	}

	fun open() {
		promptWhenAvailable = false
		show(true)
		if (job?.isActive != true && status !is UpdateStatus.Ready) check()
	}

	fun dismiss() {
		promptWhenAvailable = false
		show(false)
	}

	private fun show(value: Boolean) {
		savedState["visible"] = value
		visible = value
	}

	fun selectChannel(value: UpdateChannel) {
		if (channel == value) return
		channel = value
		prefs.edit().putString("channel", value.id).apply()
		check()
	}

	fun check() {
		savedState.remove<String>("verified")
		val selected = channel
		prefs.edit().putLong("last-${selected.id}", System.currentTimeMillis()).apply()
		status = UpdateStatus.Checking
		runOperation(R.string.update_check_failed) {
			val update = github.check(selected)
			status = if (update == null) UpdateStatus.Current else UpdateStatus.Available(update)
			if (update != null && promptWhenAvailable) show(true)
			promptWhenAvailable = false
		}
	}

	fun download() {
		val update = (status as? UpdateStatus.Available)?.update ?: return
		status = UpdateStatus.Downloading(0)
		runOperation(R.string.update_download_failed) {
			val file = github.download(update) { percent -> status = UpdateStatus.Downloading(percent) }
			status = UpdateStatus.Ready(update, file)
			savedState["verified"] = Json.encodeToString(UpdateManifest.serializer(), update.manifest)
		}
	}

	private fun runOperation(
		failureMessage: Int,
		block: suspend () -> Unit,
	) {
		job?.cancel()
		job =
			viewModelScope.launch {
				try {
					block()
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (failure: Exception) {
					ensureActive()
					val message =
						when (failure) {
							is InvalidUpdate -> R.string.update_invalid
							is NativeTlsUnavailable -> R.string.update_tls_unavailable
							is UnsupportedAbi -> R.string.update_no_abi
							else -> failureMessage
						}
					status = UpdateStatus.Failed(message)
				}
			}
	}
}
