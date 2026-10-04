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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rs.clash.android.R
import rs.clash.android.ui.snackbar.SnackbarController
import kotlinx.coroutines.withContext
import org.json.JSONArray
import rs.clash.android.Global
import rs.clash.android.model.Profile
import rs.clash.android.model.ProfileType
import uniffi.clash_android_ffi.DownloadProgress
import uniffi.clash_android_ffi.DownloadProgressCallback
import uniffi.clash_android_ffi.EyreException
import uniffi.clash_android_ffi.downloadFileWithProgress
import uniffi.clash_android_ffi.formatEyreError
import uniffi.clash_android_ffi.verifyConfig
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class FileInfo(
	val name: String,
	val uri: Uri,
	val size: Long = 0,
)

class ProfileViewModel(
	application: Application,
) : AndroidViewModel(application) {
	private val prefs = Global.application.getSharedPreferences("file_prefs", Context.MODE_PRIVATE)
	var selectedFile by mutableStateOf<FileInfo?>(null)
		private set

	var isImporting by mutableStateOf(false)
		private set

	var savedFilePath by mutableStateOf<String?>(null)
		private set

	var isVerifying by mutableStateOf(false)
		private set

	var verificationResult by mutableStateOf<String?>(null)
		private set

	// Multiple profiles support
	val profiles = mutableStateListOf<Profile>()
	
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

	private fun saveProfiles() {
		val jsonArray = JSONArray()
		profiles.forEach { profile ->
			jsonArray.put(profile.asJsonObject())
		}
		
		prefs.edit {
			putString("profiles_list", jsonArray.toString())
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
				// Immediately update Global.profilePath
				Global.profilePath = file.absolutePath
			}
			
			saveProfiles()

			SnackbarController.showMessage(
				getApplication<Application>().getString(R.string.profile_import_success),
			)
			file.absolutePath
		}  catch (e: EyreException) {
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
		// Deactivate all profiles
		val updatedProfiles = profiles.map { it.copy(isActive = false) }
		profiles.clear()
		profiles.addAll(updatedProfiles)
		
		// Activate selected profile
		val index = profiles.indexOfFirst { it.id == profile.id }
		if (index >= 0) {
			profiles[index] = profiles[index].copy(isActive = true)
			activeProfile = profiles[index]
			savedFilePath = profiles[index].filePath
			
			// Update SharedPreferences
			prefs.edit {
				putString("profile_path", profiles[index].filePath)
			}
			// Immediately update Global.profilePath
			Global.profilePath = profiles[index].filePath
			
			saveProfiles()
			SnackbarController.showMessage(
				getApplication<Application>().getString(R.string.profile_activate_success, profile.name),
			)
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
			// Immediately clear Global.profilePath
			Global.profilePath = ""
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

	fun verifyCurrentConfig(context: Context) {
		if (savedFilePath == null) {
			verificationResult = getApplication<Application>().getString(R.string.profile_not_found)
			return
		}

		isVerifying = true
		verificationResult = null

		try {
			val (isValid, content) = verify(savedFilePath!!)
			verificationResult =
				if (isValid) {
					getApplication<Application>().getString(R.string.profile_verify_valid, content)
				} else {
					getApplication<Application>().getString(R.string.profile_verify_failed, content)
				}
		} catch (e: Exception) {
			verificationResult = e.message
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

	fun addRemoteProfile(
		context: Context,
		profileName: String,
		url: String,
		autoUpdate: Boolean = false,
		userAgent: String? = null,
		proxyUrl: String? = null,
	) {
		viewModelScope.launch {
			isDownloading = true
			downloadProgress = null
			try {
				// Create unique file name based on profile name
				val fileName = profileName.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]"), "_")
				val file = File(context.filesDir, fileName)
				
				// Auto-detect proxy if VPN is running and user didn't specify one
				val effectiveProxyUrl =
					proxyUrl ?: Global.clashInstance?.mixedPort()?.let { port ->
						"http://127.0.0.1:$port"
					}
				
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
							// Immediately update Global.profilePath
							Global.profilePath = file.absolutePath
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
			} finally {
				isDownloading = false
				downloadProgress = null
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
		
		viewModelScope.launch {
			isDownloading = true
			downloadProgress = null
			try {
				withContext(Dispatchers.IO) {
					val file = File(profile.filePath)
					// Download to a scratch file and only replace the profile once the
					// payload was fetched *and* validated. Downloading straight onto
					// `file` used to destroy the last working configuration whenever the
					// update failed halfway through or the new config was rejected.
					val tempFile = File(file.parentFile, "${file.name}.download")
					// Use provided parameters or fall back to profile's stored values
					val effectiveUserAgent = userAgent ?: profile.userAgent
					val effectiveProxyUrl = proxyUrl
						?: profile.proxyUrl
						?: Global.clashInstance?.mixedPort()?.let { port ->
							"http://127.0.0.1:$port"
						}
					
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
						try {
							downloadFileWithProgress(
								profile.url,
								tempFile.absolutePath,
								effectiveUserAgent,
								effectiveProxyUrl,
								progressCallback,
							)
						} catch (e: EyreException) {
							tempFile.delete()
							SnackbarController.showMessage(
								getApplication<Application>()
									.getString(R.string.profile_remote_update_failed, formatEyreError(e)),
							)
							return@withContext
						} catch (e: Exception) {
							tempFile.delete()
							SnackbarController.showMessage(
								getApplication<Application>().getString(
									R.string.profile_remote_update_failed,
									e.message ?: e.toString(),
								),
							)
							return@withContext
						}
					
					if (!result.success) {
						tempFile.delete()
						SnackbarController.showMessage(
							getApplication<Application>().getString(
								R.string.profile_remote_update_failed,
								result.errorMessage
									?: getApplication<Application>().getString(R.string.profile_unknown_error),
							),
						)
						return@withContext
					}
					
					// Verify the downloaded config before it replaces the current one
					val (isValid, error) = verify(tempFile.absolutePath)
					if (!isValid) {
						tempFile.delete()
						SnackbarController.showMessage(
							getApplication<Application>()
								.getString(R.string.profile_remote_verify_failed, error),
						)
						return@withContext
					}
					
					// Promote the verified download to the real profile file.
					if (file.exists() && !file.delete()) {
						tempFile.delete()
						SnackbarController.showMessage(
							getApplication<Application>().getString(
								R.string.profile_remote_update_failed,
								"cannot replace ${file.name}",
							),
						)
						return@withContext
					}
					if (!tempFile.renameTo(file)) {
						tempFile.delete()
						SnackbarController.showMessage(
							getApplication<Application>().getString(
								R.string.profile_remote_update_failed,
								"cannot write ${file.name}",
							),
						)
						return@withContext
					}
					
					withContext(Dispatchers.Main) {
						// Update profile
						val index = profiles.indexOfFirst { it.id == profile.id }
						if (index >= 0) {
							profiles[index] =
								profiles[index].copy(
									fileSize = result.fileSize.toLong(),
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
			} catch (e: Exception) {
				SnackbarController.showMessage(
					getApplication<Application>().getString(
						R.string.profile_remote_update_failed,
						e.message ?: e.toString(),
					),
				)
			} finally {
				isDownloading = false
				downloadProgress = null
			}
		}
	}
}
