package rs.clash.android.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import rs.clash.android.Global
import rs.clash.android.R
import rs.clash.android.data.AppData
import rs.clash.android.model.Profile
import rs.clash.android.model.ProfileType
import rs.clash.android.ui.snackbar.SnackbarController
import uniffi.clash_android_ffi.DownloadProgress
import uniffi.clash_android_ffi.DownloadProgressCallback
import uniffi.clash_android_ffi.EyreException
import uniffi.clash_android_ffi.SubscriptionUpdate
import uniffi.clash_android_ffi.downloadFileWithProgress
import uniffi.clash_android_ffi.formatEyreError
import uniffi.clash_android_ffi.updateSubscription
import uniffi.clash_android_ffi.verifyConfig
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class FileInfo(
	val name: String,
	val uri: Uri,
	val size: Long = 0,
)

data class VerificationResult(
	val isValid: Boolean,
	val detail: String?,
)

class ProfileViewModel(
	application: Application,
) : AndroidViewModel(application) {
	companion object {
		// Download jobs can outlive their screen. Accessed on the UI thread.
		private var activeDownloads = 0
		internal val hasActiveDownloads: Boolean get() = activeDownloads > 0
	}

	val vpnSession = AppData.vpn.session

	private val prefs = Global.application.getSharedPreferences("file_prefs", Context.MODE_PRIVATE)
	var selectedFile by mutableStateOf<FileInfo?>(null)
		private set

	var isImporting by mutableStateOf(false)
		private set

	var savedFilePath by mutableStateOf<String?>(null)
		private set

	var isVerifying by mutableStateOf(false)
		private set

	var verificationResult by mutableStateOf<VerificationResult?>(null)
		private set

	// Multiple profiles support
	val profiles = mutableStateListOf<Profile>()
	internal val transfer = ProfileTransfer(application, viewModelScope, ::importReceivedProfile)

	private suspend fun importReceivedProfile(
		source: File,
		offeredName: String,
		url: String?,
	): String {
		// Keep the sender's name out of filesystem paths. Existing local and subscription
		// imports use filesDir/<display name>, so reserve a separate directory for LAN copies.
		val directory = File(getApplication<Application>().filesDir, "received-profiles")
		check(directory.isDirectory || directory.mkdir())
		val file = File.createTempFile("profile-", ".yaml", directory)
		var imported = false
		try {
			withContext(Dispatchers.IO) {
				source.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
				verifyConfig(file.absolutePath)
			}
			val base = sanitizeProfileFileName(offeredName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'))
			val names = profiles.mapTo(mutableSetOf()) { it.name }
			val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base ($it)" }.first { it !in names }
			val profile =
				Profile(
					name = name,
					filePath = file.absolutePath,
					fileSize = file.length(),
					type = if (url == null) ProfileType.LOCAL else ProfileType.REMOTE,
					url = url,
					isActive =
						profiles.isEmpty() && savedFilePath == null,
				)
			val list = JSONArray()
			(profiles + profile).forEach { list.put(it.asJsonObject()) }
			prefs.edit {
				putString("profiles_list", list.toString())
				if (profile.isActive) putString("profile_path", profile.filePath)
			}
			profiles.add(profile)
			if (profile.isActive) {
				activeProfile = profile
				savedFilePath = profile.filePath
			}
			imported = true
			return name
		} finally {
			if (!imported) file.delete()
		}
	}

	var activeProfile by mutableStateOf<Profile?>(null)
		private set

	fun selectFile(
		context: Context,
		uri: Uri,
	) {
		val cursor = context.contentResolver.query(uri, null, null, null, null)
		var fileName = "config.yaml"
		var fileSize = 0L

		cursor?.use {
			if (it.moveToFirst()) {
				val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
				val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
				if (nameIndex != -1) {
					fileName = it.getString(nameIndex)
				}
				if (sizeIndex != -1) {
					fileSize = it.getLong(sizeIndex)
				}
			}
		}

		selectedFile = FileInfo(fileName, uri, fileSize)
	}

	fun clearSelection() {
		selectedFile = null
	}

	fun loadSavedFilePath() {
		savedFilePath = prefs.getString("profile_path", null)
		// Load profiles list
		loadProfiles()
	}

	private fun loadProfiles() {
		val profilesJson = prefs.getString("profiles_list", null)
		
		profiles.clear()
		if (profilesJson != null) {
			try {
				val jsonArray = JSONArray(profilesJson)
				val loadedProfiles = mutableListOf<Profile>()
				
				for (i in 0 until jsonArray.length()) {
					val jsonObject = jsonArray.getJSONObject(i)
					val profile = Profile(jsonObject)
					loadedProfiles.add(profile)
				}
				
				profiles.addAll(loadedProfiles)
				
				// Update active profile
				activeProfile = profiles.firstOrNull { it.isActive }
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}
	}

	private fun saveProfiles(activePath: String? = null) {
		val jsonArray = JSONArray()
		profiles.forEach { profile ->
			jsonArray.put(profile.asJsonObject())
		}
		
		prefs.edit {
			putString("profiles_list", jsonArray.toString())
			if (activePath != null) putString("profile_path", activePath)
		}
	}

	fun saveFileToAppDirectory(
		context: Context,
		uri: Uri,
		profileName: String? = null,
	): String? {
		isImporting = true
		return try {
			val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
			val rawName =
				profileName ?: selectedFile?.name?.substringBeforeLast('.') ?: "profile_${System.currentTimeMillis()}"
			// The name comes straight from the import dialog, so it has to be sanitised:
			// without this a name like `../../shared_prefs/settings.xml` would create (and
			// truncate) a file outside the app's files directory.
			val fileName = sanitizeProfileFileName(rawName)
			
			// Create unique file name
			val file = File(context.filesDir, fileName)
			if (file.exists()) {
				file.delete()
			}
			file.createNewFile()
			
			var fileSize = 0L
			inputStream?.use { input ->
				FileOutputStream(file).use { output ->
					fileSize = input.copyTo(output)
				}
			}

			savedFilePath = file.absolutePath

			// Add to profiles list
			val isFirstProfile = profiles.isEmpty()
			val newProfile =
				Profile(
					name = fileName,
					filePath = file.absolutePath,
					fileSize = fileSize,
					isActive = isFirstProfile, // Only first profile becomes active
				)
			
			profiles.add(newProfile)
			
			// If this is the first profile, set it as active
			if (isFirstProfile) {
				activeProfile = newProfile
				// Update SharedPreferences for active profile
				prefs.edit {
					putString("profile_path", file.absolutePath)
				}
			}
			
			saveProfiles()

			SnackbarController.showMessage(
				getApplication<Application>().getString(R.string.profile_import_success),
			)
			file.absolutePath
		} catch (e: EyreException) {
			SnackbarController.showMessage(
				getApplication<Application>()
					.getString(R.string.profile_import_failed, formatEyreError(e)),
			)
			null
		} catch (e: Exception) {
			val errorMessage = e.message ?: e.toString()
			SnackbarController.showMessage(
				getApplication<Application>().getString(R.string.profile_import_failed, errorMessage),
			)
			null
		} finally {
			isImporting = false
		}
	}

	/**
	 * Turns an arbitrary user supplied profile name into a plain file name inside the
	 * app's files directory: path separators and traversal segments are replaced, and
	 * an empty result falls back to a generated name.
	 */
	private fun sanitizeProfileFileName(rawName: String): String {
		val sanitized =
			rawName
				.trim()
				.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
				.trim('.', ' ')
				.take(64)
		return if (sanitized.isEmpty() || sanitized == "..") {
			"profile_${System.currentTimeMillis()}"
		} else {
			sanitized
		}
	}

	fun activateProfile(
		context: Context,
		profile: Profile,
	) {
		val selected = profiles.firstOrNull { it.id == profile.id } ?: return
		if (selected.isActive) return

		for (index in profiles.indices) {
			val entry = profiles[index]
			val active = entry.id == selected.id
			if (entry.isActive != active) profiles[index] = entry.copy(isActive = active)
		}
		activeProfile = profiles.first { it.id == selected.id }
		savedFilePath = selected.filePath
		saveProfiles(activePath = selected.filePath)

		viewModelScope.launch {
			val message = restartVpnForConfigChange(context) ?: context.getString(R.string.profile_activate_success, selected.name)
			if (activeProfile?.id == selected.id) SnackbarController.showMessage(message)
		}
	}

	fun deleteProfile(
		context: Context,
		profile: Profile,
	) {
		val file = File(profile.filePath)
		if (file.exists()) {
			file.delete()
		}
		
		profiles.removeAll { it.id == profile.id }
		
		// If deleted profile was active, activate the first remaining profile
		if (profile.isActive && profiles.isNotEmpty()) {
			activateProfile(context, profiles[0])
		} else if (profiles.isEmpty()) {
			activeProfile = null
			savedFilePath = null
			prefs.edit {
				remove("profile_path")
			}
		}
		
		saveProfiles()
		SnackbarController.showMessage(
			getApplication<Application>().getString(R.string.profile_deleted, profile.name),
		)
	}

	fun renameProfile(
		context: Context,
		profile: Profile,
		newName: String,
	) {
		val index = profiles.indexOfFirst { it.id == profile.id }
		if (index >= 0) {
			profiles[index] = profiles[index].copy(name = newName)
			if (profiles[index].isActive) {
				activeProfile = profiles[index]
			}
			saveProfiles()
			SnackbarController.showMessage(
				getApplication<Application>().getString(R.string.profile_renamed),
			)
		}
	}

	fun verify(path: String): Pair<Boolean, String> =
		try {
			true to verifyConfig(path)
		} catch (e: EyreException) {
			false to formatEyreError(e)
		}

	fun verifyCurrentConfig() {
		val path = activeProfile?.filePath ?: savedFilePath
		isVerifying = true
		try {
			verificationResult =
				if (path == null) {
					VerificationResult(false, null)
				} else {
					val (isValid, detail) = verify(path)
					VerificationResult(isValid, detail)
				}
		} catch (e: Exception) {
			verificationResult = VerificationResult(false, e.message ?: e.toString())
		} finally {
			isVerifying = false
		}
	}

	fun clearVerificationResult() {
		verificationResult = null
	}

	// Remote profile download
	var isDownloading by mutableStateOf(false)
		private set

	// Download progress state
	var downloadProgress by mutableStateOf<DownloadProgress?>(null)
		private set

	private fun launchDownload(download: suspend () -> Unit) {
		viewModelScope.launch {
			activeDownloads++
			isDownloading = true
			downloadProgress = null
			try {
				download()
			} finally {
				activeDownloads--
				isDownloading = false
				downloadProgress = null
			}
		}
	}

	fun addRemoteProfile(
		context: Context,
		profileName: String,
		url: String,
		autoUpdate: Boolean = false,
		userAgent: String? = null,
		proxyUrl: String? = null,
	) {
		launchDownload {
			try {
				// Create unique file name based on profile name
				val fileName = profileName.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]"), "_")
				val file = File(context.filesDir, fileName)
				
				// Auto-detect proxy if VPN is running and user didn't specify one
				val effectiveProxyUrl =
					proxyUrl ?: vpnSession.value.localProxyUrl
				
				// Progress callback - update on main thread
				val progressCallback =
					object : DownloadProgressCallback {
						override fun onProgress(progress: DownloadProgress) {
							Log.d("ProfileViewModel", "Download progress: ${progress.downloaded}/${progress.total}")
							viewModelScope.launch(Dispatchers.Main) {
								downloadProgress = progress
								Log.d("ProfileViewModel", "Progress updated on main thread")
							}
						}
					}
				
				// Download config from URL using Rust FFI
				withContext(Dispatchers.IO) {
					val result =
						downloadFileWithProgress(
							url,
							file.absolutePath,
							userAgent,
							effectiveProxyUrl,
							progressCallback,
						)
					
					if (!result.success) {
						SnackbarController.showMessage(
							getApplication<Application>().getString(
								R.string.profile_remote_add_failed,
								result.errorMessage
									?: getApplication<Application>().getString(R.string.profile_unknown_error),
							),
						)

						return@withContext
					}
					
					// Verify the downloaded config
					val (isValid, _) = verify(file.absolutePath)
					if (!isValid) {
						file.delete()
						SnackbarController.showMessage(
							getApplication<Application>().getString(R.string.profile_remote_verify_failed_removed),
						)
						return@withContext
					}
					
					withContext(Dispatchers.Main) {
						// Add to profiles list
						val isFirstProfile = profiles.isEmpty()
						val newProfile =
							Profile(
								name = profileName,
								filePath = file.absolutePath,
								fileSize = result.fileSize.toLong(),
								isActive = isFirstProfile,
								type = ProfileType.REMOTE,
								url = url,
								lastUpdated = System.currentTimeMillis(),
								autoUpdate = autoUpdate,
								userAgent = userAgent,
								proxyUrl = proxyUrl,
							)
						profiles.add(newProfile)
						
						// If this is the first profile, set it as active
						if (isFirstProfile) {
							activeProfile = newProfile
							// Update SharedPreferences for active profile
							prefs.edit {
								putString("profile_path", file.absolutePath)
							}
						}
						
						saveProfiles()
						SnackbarController.showMessage(
							getApplication<Application>().getString(R.string.profile_remote_added),
						)
					}
				}
			} catch (e: EyreException) {
				SnackbarController.showMessage(
					getApplication<Application>()
						.getString(R.string.profile_remote_add_failed, formatEyreError(e)),
				)
			} catch (e: Exception) {
				SnackbarController.showMessage(
					getApplication<Application>().getString(
						R.string.profile_remote_add_failed,
						e.message ?: e.toString(),
					),
				)
			}
		}
	}

	fun updateRemoteProfile(
		context: Context,
		profile: Profile,
		userAgent: String? = null,
		proxyUrl: String? = null,
	) {
		if (profile.type != ProfileType.REMOTE || profile.url == null) {
			SnackbarController.showMessage(
				getApplication<Application>().getString(R.string.profile_update_remote_only),
			)
			return
		}
		
		launchDownload {
			try {
				withContext(Dispatchers.IO) {
					// Use provided parameters or fall back to profile's stored values
					val effectiveUserAgent = userAgent ?: profile.userAgent
					val effectiveProxyUrl =
						proxyUrl
							?: profile.proxyUrl
							?: vpnSession.value.localProxyUrl
					
					// Progress callback - update on main thread
					val progressCallback =
						object : DownloadProgressCallback {
							override fun onProgress(progress: DownloadProgress) {
								viewModelScope.launch(Dispatchers.Main) {
									downloadProgress = progress
								}
							}
						}
					
					val result =
						updateSubscription(
							profile.url,
							profile.filePath,
							effectiveUserAgent,
							effectiveProxyUrl,
							progressCallback,
						)
					val fileSize =
						when (result) {
							is SubscriptionUpdate.Updated -> result.fileSize.toLong()
							is SubscriptionUpdate.InvalidConfig -> {
								SnackbarController.showMessage(
									getApplication<Application>().getString(R.string.profile_remote_verify_failed, result.detail),
								)
								return@withContext
							}
						}

					withContext(Dispatchers.Main) {
						// Update profile
						val index = profiles.indexOfFirst { it.id == profile.id }
						if (index >= 0) {
							profiles[index] =
								profiles[index].copy(
									fileSize = fileSize,
									lastUpdated = System.currentTimeMillis(),
								)
							if (profiles[index].isActive) {
								activeProfile = profiles[index]
							}
							saveProfiles()
						}
						
						SnackbarController.showMessage(
							getApplication<Application>().getString(R.string.profile_config_updated),
						)
					}
				}
			} catch (e: EyreException) {
				SnackbarController.showMessage(
					getApplication<Application>()
						.getString(R.string.profile_remote_update_failed, formatEyreError(e)),
				)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				SnackbarController.showMessage(
					getApplication<Application>().getString(
						R.string.profile_remote_update_failed,
						e.message ?: e.toString(),
					),
				)
			}
		}
	}
}
