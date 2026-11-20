package rs.clash.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rs.clash.android.R
import rs.clash.android.formatSize
import rs.clash.android.model.Profile
import rs.clash.android.viewmodel.VerificationResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ActiveProfileCard(
	profile: Profile,
	onVerify: () -> Unit,
	isVerifying: Boolean,
	modifier: Modifier = Modifier,
) {
	ElevatedCard(
		modifier = modifier,
		colors =
			CardDefaults.elevatedCardColors(
				containerColor = MaterialTheme.colorScheme.primaryContainer,
			),
	) {
		Column(modifier = Modifier.padding(16.dp)) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(
					Icons.Default.CheckCircle,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(40.dp),
				)
				Spacer(modifier = Modifier.width(16.dp))
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = stringResource(R.string.profile_active_config),
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.primary,
					)
					Spacer(modifier = Modifier.height(4.dp))
					Text(
						text = profile.name,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
					)
					Spacer(modifier = Modifier.height(2.dp))
					Text(
						text = formatFileSize(profile.fileSize),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			
			Spacer(modifier = Modifier.height(12.dp))
			
			FilledTonalButton(
				onClick = onVerify,
				modifier = Modifier.fillMaxWidth(),
				enabled = !isVerifying,
			) {
				if (isVerifying) {
					CircularProgressIndicator(
						modifier = Modifier.size(16.dp),
						color = MaterialTheme.colorScheme.onSecondaryContainer,
						strokeWidth = 2.dp,
					)
					Spacer(modifier = Modifier.width(8.dp))
					Text(stringResource(R.string.verifying))
				} else {
					Icon(
						Icons.Default.VerifiedUser,
						contentDescription = null,
						modifier = Modifier.size(16.dp),
					)
					Spacer(modifier = Modifier.width(8.dp))
					Text(stringResource(R.string.profile_verify_btn))
				}
			}
		}
	}
}

@Composable
fun ProfileCard(
	profile: Profile,
	onSend: () -> Unit,
	onActivate: () -> Unit,
	onDelete: () -> Unit,
	onRename: (String) -> Unit,
	onUpdate: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	val showMenu = remember { mutableStateOf(false) }
	val showRenameDialog = remember { mutableStateOf(false) }
	val newName = remember { mutableStateOf(profile.name) }
	val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
	
	if (showRenameDialog.value) {
		AlertDialog(
			onDismissRequest = {
				showRenameDialog.value = false
				newName.value = profile.name
			},
			title = { Text(stringResource(R.string.profile_rename)) },
			text = {
				OutlinedTextField(
					value = newName.value,
					onValueChange = { newName.value = it },
					label = { Text(stringResource(R.string.profile_config_name)) },
					singleLine = true,
				)
			},
			confirmButton = {
				TextButton(
					onClick = {
						if (newName.value.isNotBlank()) {
							onRename(newName.value)
							showRenameDialog.value = false
						}
					},
				) {
					Text(stringResource(R.string.confirm))
				}
			},
			dismissButton = {
				TextButton(
					onClick = {
						showRenameDialog.value = false
						newName.value = profile.name
					},
				) {
					Text(stringResource(R.string.cancel))
				}
			},
		)
	}
	
	OutlinedCard(
		modifier = modifier.clickable(enabled = !profile.isActive) { onActivate() },
	) {
		Row(
			modifier = Modifier.padding(16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				if (profile.isActive) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
				contentDescription = if (profile.isActive) "Active" else "Inactive",
				tint = if (profile.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
				modifier = Modifier.size(24.dp),
			)
			Spacer(modifier = Modifier.width(16.dp))
			Column(modifier = Modifier.weight(1f)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = profile.name,
						style = MaterialTheme.typography.bodyLarge,
						fontWeight = if (profile.isActive) FontWeight.Bold else FontWeight.Normal,
					)
					if (profile.type == rs.clash.android.model.ProfileType.REMOTE) {
						Spacer(modifier = Modifier.width(4.dp))
						Icon(
							Icons.Default.Refresh,
							contentDescription = "Remote",
							tint = MaterialTheme.colorScheme.primary,
							modifier = Modifier.size(16.dp),
						)
					}
				}
				Spacer(modifier = Modifier.height(2.dp))
				Text(
					text = formatFileSize(profile.fileSize),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Text(
					text = dateFormat.format(Date(profile.createdAt)),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (profile.type == rs.clash.android.model.ProfileType.REMOTE && profile.lastUpdated != null) {
					Text(
						text = "${stringResource(R.string.profile_update_config)}: ${dateFormat.format(Date(profile.lastUpdated))}",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.primary,
					)
				}
			}
			
			Box {
				IconButton(onClick = { showMenu.value = true }) {
					Icon(
						Icons.Default.MoreVert,
						contentDescription = "More options",
					)
				}
				
				DropdownMenu(
					expanded = showMenu.value,
					onDismissRequest = { showMenu.value = false },
				) {
					DropdownMenuItem(
						text = { Text(stringResource(R.string.lan_send)) },
						onClick = {
							showMenu.value = false
							onSend()
						},
						leadingIcon = {
							Icon(Icons.Default.Share, contentDescription = null)
						},
					)
					if (!profile.isActive) {
						DropdownMenuItem(
							text = { Text(stringResource(R.string.profile_set_active)) },
							onClick = {
								onActivate()
								showMenu.value = false
							},
							leadingIcon = {
								Icon(Icons.Default.CheckCircle, contentDescription = null)
							},
						)
					}
					if (profile.type == rs.clash.android.model.ProfileType.REMOTE && onUpdate != null) {
						DropdownMenuItem(
							text = { Text(stringResource(R.string.profile_update_config)) },
							onClick = {
								onUpdate()
								showMenu.value = false
							},
							leadingIcon = {
								Icon(Icons.Default.Refresh, contentDescription = null)
							},
						)
					}
					DropdownMenuItem(
						text = { Text(stringResource(R.string.profile_rename)) },
						onClick = {
							showRenameDialog.value = true
							showMenu.value = false
						},
						leadingIcon = {
							Icon(Icons.Default.Edit, contentDescription = null)
						},
					)
					DropdownMenuItem(
						text = { Text(stringResource(R.string.profile_delete)) },
						onClick = {
							onDelete()
							showMenu.value = false
						},
						leadingIcon = {
							Icon(
								Icons.Default.Delete,
								contentDescription = null,
								tint = MaterialTheme.colorScheme.error,
							)
						},
					)
				}
			}
		}
	}
}

@Composable
fun VerificationResultCard(
	result: VerificationResult,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val isValid = result.isValid
	val scrollState = rememberScrollState()
	
	ElevatedCard(
		modifier = modifier,
		colors =
			CardDefaults.elevatedCardColors(
				containerColor =
					if (isValid) {
						MaterialTheme.colorScheme.tertiaryContainer
					} else {
						MaterialTheme.colorScheme.errorContainer
					},
			),
	) {
		Row(
			modifier = Modifier.padding(16.dp),
			verticalAlignment = Alignment.Top,
		) {
			Icon(
				if (isValid) Icons.Default.CheckCircle else Icons.Default.Error,
				contentDescription = null,
				tint =
					if (isValid) {
						MaterialTheme.colorScheme.tertiary
					} else {
						MaterialTheme.colorScheme.error
					},
				modifier = Modifier.size(24.dp),
			)
			Spacer(modifier = Modifier.width(12.dp))
			Column(
				modifier =
					Modifier
						.weight(1f)
						.height(300.dp)
						.verticalScroll(scrollState),
			) {
				Text(
					text = stringResource(R.string.validation_result),
					style = MaterialTheme.typography.labelMedium,
					color =
						if (isValid) {
							MaterialTheme.colorScheme.onTertiaryContainer
						} else {
							MaterialTheme.colorScheme.onErrorContainer
						},
				)
				Spacer(modifier = Modifier.height(4.dp))
				Text(
					text =
						if (result.detail == null) {
							stringResource(R.string.profile_not_found)
						} else {
							stringResource(if (isValid) R.string.profile_verify_valid else R.string.profile_verify_failed, result.detail)
						},
					style = MaterialTheme.typography.bodySmall,
					color =
						if (isValid) {
							MaterialTheme.colorScheme.onTertiaryContainer
						} else {
							MaterialTheme.colorScheme.onErrorContainer
						},
					fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
				)
			}
			IconButton(
				onClick = onDismiss,
				modifier = Modifier.size(24.dp),
			) {
				Icon(
					Icons.Default.Close,
					contentDescription = "Close",
					tint =
						if (isValid) {
							MaterialTheme.colorScheme.onTertiaryContainer
						} else {
							MaterialTheme.colorScheme.onErrorContainer
						},
					modifier = Modifier.size(20.dp),
				)
			}
		}
	}
}

fun formatFileSize(size: Long): String = formatSize(size)
