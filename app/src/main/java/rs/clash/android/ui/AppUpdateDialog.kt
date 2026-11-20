package rs.clash.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import rs.clash.android.R
import rs.clash.android.update.UpdateChannel
import rs.clash.android.viewmodel.AppUpdateViewModel
import rs.clash.android.viewmodel.UpdateStatus

internal val UpdateChannel.labelRes: Int
	get() =
		when (this) {
			UpdateChannel.STABLE -> R.string.update_stable
			UpdateChannel.TESTING -> R.string.update_testing
		}

@Composable
internal fun AppUpdateDialog(viewModel: AppUpdateViewModel) {
	val context = LocalContext.current
	val status = viewModel.status
	AlertDialog(
		onDismissRequest = viewModel::dismiss,
		title = { Text(stringResource(R.string.update_title)) },
		text = {
			Column(
				modifier = Modifier.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(stringResource(R.string.update_channel), style = MaterialTheme.typography.labelLarge)
				Column(modifier = Modifier.selectableGroup()) {
					UpdateChannel.entries.forEach { channel ->
						PreferenceOption(
							text = stringResource(channel.labelRes),
							selected = viewModel.channel == channel,
							onClick = { viewModel.selectChannel(channel) },
						)
					}
				}
				HorizontalDivider()
				when (status) {
					UpdateStatus.Idle, UpdateStatus.Checking -> Text(stringResource(R.string.update_checking))
					UpdateStatus.Current -> Text(stringResource(R.string.update_current))
					is UpdateStatus.Downloading -> Text(stringResource(R.string.update_downloading, status.percent))
					is UpdateStatus.Failed -> Text(stringResource(status.message))
					is UpdateStatus.Available -> {
						val update = status.update
						Text(stringResource(R.string.update_available, update.manifest.versionName, update.abi))
						if (update.manifest.notes.isNotBlank()) Text(update.manifest.notes)
					}
					is UpdateStatus.Ready -> {
						Text(stringResource(R.string.update_verified, status.update.manifest.versionName))
					}
				}
			}
		},
		confirmButton = {
			when (status) {
				is UpdateStatus.Available -> {
					TextButton(onClick = viewModel::download) { Text(stringResource(R.string.update_download)) }
				}
				is UpdateStatus.Ready -> {
					TextButton(onClick = { requestInstall(context, status, viewModel::check) }) {
						Text(stringResource(R.string.update_install))
					}
				}
				is UpdateStatus.Failed, UpdateStatus.Current -> {
					TextButton(onClick = viewModel::check) { Text(stringResource(R.string.update_check)) }
				}
				else -> Unit
			}
		},
		dismissButton = {
			TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.update_later)) }
		},
	)
}

@Suppress("DEPRECATION")
private fun requestInstall(
	context: Context,
	ready: UpdateStatus.Ready,
	onFileMissing: () -> Unit,
) {
	if (!ready.file.isFile) {
		onFileMissing()
		return
	}
	try {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
			Toast.makeText(context, R.string.update_allow_source, Toast.LENGTH_LONG).show()
			context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
			return
		}
		val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", ready.file)
		context.startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
	} catch (_: ActivityNotFoundException) {
		Toast.makeText(context, R.string.update_no_installer, Toast.LENGTH_LONG).show()
	} catch (_: SecurityException) {
		Toast.makeText(context, R.string.update_install_denied, Toast.LENGTH_LONG).show()
	}
}
