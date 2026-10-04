package rs.clash.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import rs.clash.android.Global
import rs.clash.android.R
import rs.clash.android.model.ProfileType
import rs.clash.android.ui.components.ActiveProfileCard
import rs.clash.android.ui.components.ProfileCard
import rs.clash.android.ui.components.TextInfoDialog
import rs.clash.android.ui.components.VerificationResultCard
import rs.clash.android.ui.components.formatFileSize
import rs.clash.android.viewmodel.ProfileViewModel

@Destination<RootGraph>()
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
	navigator: DestinationsNavigator,
	modifier: Modifier = Modifier,
	vm: ProfileViewModel = viewModel(),
) {
	val context = LocalContext.current
	val showNameDialog = remember { mutableStateOf(false) }
	val profileName = remember { mutableStateOf("") }
	val showRemoteDialog = remember { mutableStateOf(false) }
	val remoteName = remember { mutableStateOf("") }
	val remoteUrl = remember { mutableStateOf("") }
	val remoteAutoUpdate = remember { mutableStateOf(false) }
	val remoteUserAgent = remember { mutableStateOf("") }
	val remoteProxyUrl = remember { mutableStateOf("") }
	val wasDownloading = remember { mutableStateOf(false) }

	var showInfoDialog by remember { mutableStateOf(false) }

	// Update dialog state
	val showUpdateDialog = remember { mutableStateOf(false) }
	val updateProfile = remember { mutableStateOf<rs.clash.android.model.Profile?>(null) }
	val updateUserAgent = remember { mutableStateOf("") }
	val updateProxyUrl = remember { mutableStateOf("") }
	val wasUpdating = remember { mutableStateOf(false) }
	
	// Auto-close remote dialog when download completes
	LaunchedEffect(vm.isDownloading) {
		if (wasDownloading.value && !vm.isDownloading && showRemoteDialog.value) {
			// Download completed successfully, close dialog and reset fields
			showRemoteDialog.value = false
			remoteName.value = ""
			remoteUrl.value = ""
			remoteAutoUpdate.value = false
			remoteUserAgent.value = ""
			remoteProxyUrl.value = ""
			wasDownloading.value = false
		} else if (wasUpdating.value && !vm.isDownloading && showUpdateDialog.value) {
			// Update completed successfully, close dialog
			showUpdateDialog.value = false
			updateProfile.value = null
			updateUserAgent.value = ""
			updateProxyUrl.value = ""
			wasUpdating.value = false
		} else if (vm.isDownloading) {
			wasDownloading.value = true
		}
	}
	
	// Load saved file path on first composition
	LaunchedEffect(Unit) {
		vm.loadSavedFilePath()
	}

	val launcher =
		rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
			uri?.let {
				vm.selectFile(context, it)
				// Show dialog to enter profile name
				showNameDialog.value = true
			}
		}

	// Info dialog
	if (showInfoDialog) {
		TextInfoDialog(
			title = stringResource(R.string.about_title),
			content = stringResource(R.string.known_issues_profile),
			onDismiss = { showInfoDialog = false },
		)
	}

	// Update profile dialog
	if (showUpdateDialog.value && updateProfile.value != null) {
		val profile = updateProfile.value!!
		val configuredProxyUrl = updateProxyUrl.value.trim().takeIf { it.isNotEmpty() }
		val effectiveConfiguredProxyUrl =
			configuredProxyUrl ?: profile.proxyUrl?.trim()?.takeIf { it.isNotEmpty() }
		val clashProxyUrl =
			Global.clashInstance?.mixedPort()?.let { port ->
				"http://127.0.0.1:$port"
			}
		AlertDialog(
			onDismissRequest = {
				if (!vm.isDownloading) {
					showUpdateDialog.value = false
					updateProfile.value = null
					updateUserAgent.value = ""
					updateProxyUrl.value = ""
				}
			},
			title = { Text(stringResource(R.string.profile_update_config)) },
			text = {
				Column(
					verticalArrangement = Arrangement.spacedBy(8.dp),
					modifier = Modifier.verticalScroll(rememberScrollState()),
				) {
					Text(
						text = profile.name,
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.Bold,
					)
					if (vm.isDownloading) {
						Text(
							text = stringResource(R.string.profile_downloading),
							style = MaterialTheme.typography.bodyMedium,
							fontWeight = FontWeight.Bold,
							color = MaterialTheme.colorScheme.primary,
						)
						val progress = vm.downloadProgress
						if (progress != null && progress.total > 0U) {
							val percentage = (progress.downloaded.toFloat() / progress.total.toFloat())
							LinearProgressIndicator(
								progress = { percentage },
								modifier = Modifier.fillMaxWidth(),
							)
							Spacer(modifier = Modifier.height(4.dp))
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween,
							) {
								Text(
									text = "${formatFileSize(progress.downloaded.toLong())} / ${formatFileSize(progress.total.toLong())}",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
								Text(
									text = "${(percentage * 100).toInt()}%",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.primary,
								)
							}
						} else {
							LinearProgressIndicator(
								modifier = Modifier.fillMaxWidth(),
							)
						}
						Spacer(modifier = Modifier.height(8.dp))
					}
					OutlinedTextField(
						value = updateUserAgent.value,
						onValueChange = { updateUserAgent.value = it },
						label = { Text(stringResource(R.string.profile_ua_label)) },
						placeholder = { Text(stringResource(R.string.profile_ua_placeholder)) },
						singleLine = true,
						modifier = Modifier.fillMaxWidth(),
						enabled = !vm.isDownloading,
					)
					OutlinedTextField(
						value = updateProxyUrl.value,
						onValueChange = { updateProxyUrl.value = it },
						label = { Text(stringResource(R.string.profile_proxy_label)) },
						placeholder = { Text(stringResource(R.string.profile_proxy_placeholder)) },
						singleLine = true,
						modifier = Modifier.fillMaxWidth(),
						enabled = !vm.isDownloading,
					)
					Text(
						text =
							when {
								effectiveConfiguredProxyUrl != null ->
									stringResource(
										R.string.profile_update_proxy_configured,
										effectiveConfiguredProxyUrl,
									)
								clashProxyUrl != null ->
									stringResource(
										R.string.profile_update_proxy_clash,
										clashProxyUrl,
									)
								else -> stringResource(R.string.profile_update_proxy_none)
							},
						style = MaterialTheme.typography.bodySmall,
						color =
							if (effectiveConfiguredProxyUrl != null || clashProxyUrl != null) {
								MaterialTheme.colorScheme.primary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							},
					)
				}
			},
			confirmButton = {
				TextButton(
					onClick = {
						vm.updateRemoteProfile(
							context,
							profile,
							updateUserAgent.value.takeIf { it.isNotBlank() },
							updateProxyUrl.value.takeIf { it.isNotBlank() },
						)
						wasUpdating.value = true
					},
					enabled = !vm.isDownloading,
				) {
					if (vm.isDownloading) {
						CircularProgressIndicator(
							modifier = Modifier.size(16.dp),
							strokeWidth = 2.dp,
						)
					} else {
						Text(stringResource(R.string.profile_update_config))
					}
				}
			},
			dismissButton = {
				TextButton(
					onClick = {
						showUpdateDialog.value = false
						updateProfile.value = null
						updateUserAgent.value = ""
						updateProxyUrl.value = ""
					},
					enabled = !vm.isDownloading,
				) {
					Text(stringResource(R.string.cancel))
				}
			},
		)
	}

	// Remote profile dialog
	if (showRemoteDialog.value) {
		AlertDialog(
			onDismissRequest = {
				if (!vm.isDownloading) {
					showRemoteDialog.value = false
					remoteName.value = ""
					remoteUrl.value = ""
					remoteAutoUpdate.value = false
					remoteUserAgent.value = ""
					remoteProxyUrl.value = ""
				}
			},
			title = { Text(stringResource(R.string.profile_add_remote)) },
			text = {
				Column(
					verticalArrangement = Arrangement.spacedBy(8.dp),
					modifier = Modifier.verticalScroll(rememberScrollState()),
				) {
					if (vm.isDownloading) {
						Text(
							text = stringResource(R.string.profile_downloading),
							style = MaterialTheme.typography.bodyMedium,
							fontWeight = FontWeight.Bold,
							color = MaterialTheme.colorScheme.primary,
						)
						
						val progress = vm.downloadProgress
						if (progress != null && progress.total > 0U) {
							// Determinate progress
							val percentage = (progress.downloaded.toFloat() / progress.total.toFloat())
							LinearProgressIndicator(
								progress = { percentage },
								modifier = Modifier.fillMaxWidth(),
							)
							Spacer(modifier = Modifier.height(4.dp))
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween,
							) {
								Text(
									text = "${formatFileSize(progress.downloaded.toLong())} / ${formatFileSize(progress.total.toLong())}",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
								Text(
									text = "${(percentage * 100).toInt()}%",
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.primary,
								)
							}
						} else {
							// Indeterminate progress
							LinearProgressIndicator(
								modifier = Modifier.fillMaxWidth(),
							)
						}
						Spacer(modifier = Modifier.height(8.dp))
					}
					
					OutlinedTextField(
						value = remoteName.value,
						onValueChange = { remoteName.value = it },
						label = { Text(stringResource(R.string.profile_config_name)) },
						placeholder = { Text(stringResource(R.string.profile_name_placeholder)) },
						singleLine = true,
						modifier = Modifier.fillMaxWidth(),
						enabled = !vm.isDownloading,
					)
					OutlinedTextField(
						value = remoteUrl.value,
						onValueChange = { remoteUrl.value = it },
						label = { Text(stringResource(R.string.profile_subscription_url)) },
						placeholder = { Text(stringResource(R.string.profile_url_placeholder)) },
						singleLine = true,
						modifier = Modifier.fillMaxWidth(),
						enabled = !vm.isDownloading,
					)
					OutlinedTextField(
						value = remoteUserAgent.value,
						onValueChange = { remoteUserAgent.value = it },
						label = { Text(stringResource(R.string.profile_ua_label)) },
						placeholder = { Text(stringResource(R.string.profile_ua_placeholder)) },
						singleLine = true,
						modifier = Modifier.fillMaxWidth(),
						enabled = !vm.isDownloading,
					)
					OutlinedTextField(
						value = remoteProxyUrl.value,
						onValueChange = { remoteProxyUrl.value = it },
						label = { Text(stringResource(R.string.profile_proxy_label)) },
						placeholder = { Text(stringResource(R.string.profile_proxy_placeholder)) },
						singleLine = true,
						modifier = Modifier.fillMaxWidth(),
						enabled = !vm.isDownloading,
					)
					Row(
						verticalAlignment = Alignment.CenterVertically,
						modifier =
							Modifier.clickable(enabled = !vm.isDownloading) {
								remoteAutoUpdate.value = !remoteAutoUpdate.value
							},
					) {
						Icon(
							if (remoteAutoUpdate.value) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
							contentDescription = null,
						)
						Spacer(modifier = Modifier.width(8.dp))
						Text(stringResource(R.string.profile_auto_update))
					}
				}
			},
			confirmButton = {
				TextButton(
					onClick = {
						if (remoteName.value.isNotBlank() && remoteUrl.value.isNotBlank()) {
							vm.addRemoteProfile(
								context,
								remoteName.value,
								remoteUrl.value,
								remoteAutoUpdate.value,
								remoteUserAgent.value.takeIf { it.isNotBlank() },
								remoteProxyUrl.value.takeIf { it.isNotBlank() },
							)
						}
					},
					enabled = remoteName.value.isNotBlank() && remoteUrl.value.isNotBlank() && !vm.isDownloading,
				) {
					if (vm.isDownloading) {
						CircularProgressIndicator(
							modifier = Modifier.size(16.dp),
							strokeWidth = 2.dp,
						)
					} else {
						Text(stringResource(R.string.confirm))
					}
				}
			},
			dismissButton = {
				TextButton(
					onClick = {
						showRemoteDialog.value = false
						remoteName.value = ""
						remoteUrl.value = ""
						remoteAutoUpdate.value = false
						remoteUserAgent.value = ""
						remoteProxyUrl.value = ""
					},
					enabled = !vm.isDownloading,
				) {
					Text(stringResource(R.string.cancel))
				}
			},
		)
	}

	// Name input dialog
	if (showNameDialog.value && vm.selectedFile != null) {
		AlertDialog(
			onDismissRequest = {
				showNameDialog.value = false
				profileName.value = ""
			},
			title = { Text(stringResource(R.string.profile_enter_config_name)) },
			text = {
				OutlinedTextField(
					value = profileName.value,
					onValueChange = { profileName.value = it },
					label = { Text(stringResource(R.string.profile_config_name)) },
					placeholder = { Text(vm.selectedFile?.name ?: stringResource(R.string.profile_my_config)) },
					singleLine = true,
				)
			},
			confirmButton = {
				TextButton(
					onClick = {
						val name =
							profileName.value.ifBlank {
								vm.selectedFile?.name?.substringBeforeLast('.') ?: "配置"
							}
						vm.saveFileToAppDirectory(context, vm.selectedFile!!.uri, name)
						vm.clearSelection()
						showNameDialog.value = false
						profileName.value = ""
					},
				) {
					Text(stringResource(R.string.confirm))
				}
			},
			dismissButton = {
				TextButton(
					onClick = {
						showNameDialog.value = false
						profileName.value = ""
						vm.clearSelection()
					},
				) {
					Text(stringResource(R.string.cancel))
				}
			},
		)
	}

	Scaffold(
		modifier = modifier,
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.profile_title)) },
				windowInsets = WindowInsets(),
				actions = {
					IconButton(onClick = { showInfoDialog = true }) {
						Icon(
							imageVector = Icons.Filled.Info,
							contentDescription = stringResource(R.string.action_about),
						)
					}
				},
			)
		},
	) { padding ->
		Column(
			modifier =
				Modifier
					.fillMaxSize()
					.padding(padding)
					.padding(16.dp)
					.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			// Current Active Profile Card
			if (vm.activeProfile != null) {
				ActiveProfileCard(
					profile = vm.activeProfile!!,
					onVerify = { vm.verifyCurrentConfig(context) },
					isVerifying = vm.isVerifying,
					modifier = Modifier.fillMaxWidth(),
				)
			} else {
				OutlinedCard(modifier = Modifier.fillMaxWidth()) {
					Column(
						modifier = Modifier.padding(16.dp),
						horizontalAlignment = Alignment.CenterHorizontally,
					) {
						Icon(
							Icons.Default.FolderOpen,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.outline,
							modifier = Modifier.size(48.dp),
						)
						Spacer(modifier = Modifier.height(8.dp))
						Text(
							text = stringResource(R.string.profile_no_active),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}

			// Profiles List
			if (vm.profiles.isNotEmpty()) {
				Text(
					text = stringResource(R.string.profile_all_profiles, vm.profiles.size),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
				)
			}

			if (vm.verificationResult != null) {
				VerificationResultCard(
					result = vm.verificationResult!!,
					onDismiss = { vm.clearVerificationResult() },
					modifier = Modifier.fillMaxWidth(),
				)
			}

			Spacer(modifier = Modifier.height(8.dp))

			// Display all profiles
			// Keyed by profile id: without a stable key each card's remembered state
			// (rename dialog text, open dropdown) follows the list position, so after
			// deleting or re-activating a profile the neighbouring card would show the
			// previous profile's name in its rename dialog.
			vm.profiles.forEach { profile ->
				key(profile.id) {
					ProfileCard(
						profile = profile,
						onActivate = { vm.activateProfile(context, profile) },
						onDelete = { vm.deleteProfile(context, profile) },
						onRename = { newName -> vm.renameProfile(context, profile, newName) },
						onUpdate =
							if (profile.type == ProfileType.REMOTE) {
								{
									updateProfile.value = profile
									updateUserAgent.value = profile.userAgent ?: ""
									updateProxyUrl.value = profile.proxyUrl ?: ""
									showUpdateDialog.value = true
								}
							} else {
								null
							},
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}

			Spacer(modifier = Modifier.height(8.dp))

			// Action Buttons
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				FilledTonalButton(
					onClick = {
						launcher.launch(arrayOf("*/*"))
					},
					modifier = Modifier.weight(1f),
				) {
					Icon(
						Icons.Default.FolderOpen,
						contentDescription = null,
						modifier = Modifier.size(20.dp),
					)
					Spacer(modifier = Modifier.width(8.dp))
					Text(stringResource(R.string.profile_local_config))
				}
				
				FilledTonalButton(
					onClick = {
						showRemoteDialog.value = true
					},
					modifier = Modifier.weight(1f),
				) {
					Icon(
						Icons.Default.Refresh,
						contentDescription = null,
						modifier = Modifier.size(20.dp),
					)
					Spacer(modifier = Modifier.width(8.dp))
					Text(stringResource(R.string.profile_remote_config))
				}
			}
		}
	}
}
