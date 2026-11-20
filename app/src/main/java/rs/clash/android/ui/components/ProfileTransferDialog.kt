package rs.clash.android.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import rs.clash.android.R
import rs.clash.android.model.Profile
import rs.clash.android.viewmodel.ProfileTransfer

@Composable
internal fun rememberProfileTransfer(transfer: ProfileTransfer): (Profile?) -> Unit {
	val context = LocalContext.current
	val owner = LocalLifecycleOwner.current
	var pending by remember { mutableStateOf<Profile?>(null) }
	var denied by remember { mutableStateOf(false) }
	val permission =
		rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
			if (granted) transfer.open(pending) else denied = true
			pending = null
		}
	DisposableEffect(owner, transfer) {
		val observer =
			LifecycleEventObserver { _, event ->
				if (event == Lifecycle.Event.ON_STOP) transfer.close()
			}
		owner.lifecycle.addObserver(observer)
		onDispose {
			owner.lifecycle.removeObserver(observer)
			transfer.close()
		}
	}

	if (denied) {
		AlertDialog(
			onDismissRequest = { denied = false },
			text = { Text(stringResource(R.string.lan_permission)) },
			confirmButton = { TextButton(onClick = { denied = false }) { Text(stringResource(R.string.confirm)) } },
		)
	}
	ProfileTransferDialog(transfer)
	return { profile ->
		if (Build.VERSION.SDK_INT >= 37 &&
			context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
		) {
			pending = profile
			permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
		} else {
			transfer.open(profile)
		}
	}
}

@Composable
private fun ProfileTransferDialog(transfer: ProfileTransfer) {
	val state = transfer.state ?: return
	var address by remember { mutableStateOf("") }
	var pin by remember { mutableStateOf("") }
	val offer = state.offer
	AlertDialog(
		onDismissRequest = transfer::close,
		title = { Text(stringResource(if (state.profile == null) R.string.lan_receive else R.string.lan_send)) },
		text = {
			Column(
				modifier = Modifier.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				if (state.address.isNotEmpty()) Text(state.address)
				if (state.message.isNotBlank()) Text(state.message)
				if (!state.finished) {
					if (state.busy || state.address.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
					if (offer != null) {
						Text(stringResource(R.string.lan_offer, offer.alias, offer.name, formatFileSize(offer.size.toLong())))
					} else if (state.profile == null) {
						Text(stringResource(if (state.busy) R.string.lan_receiving else R.string.lan_waiting))
					} else {
						Text(state.profile.name)
						if (state.peers.isEmpty()) Text(stringResource(R.string.lan_discovering))
						state.peers.forEach { peer ->
							TextButton(enabled = !state.busy, onClick = { transfer.send(peer, pin) }) {
								Text("${peer.alias} · ${peer.address}")
							}
						}
						OutlinedTextField(
							value = pin,
							onValueChange = { pin = it },
							singleLine = true,
							visualTransformation = PasswordVisualTransformation(),
							label = { Text(stringResource(R.string.lan_pin)) },
						)
						OutlinedTextField(
							value = address,
							onValueChange = { address = it },
							singleLine = true,
							label = { Text(stringResource(R.string.lan_address)) },
						)
						TextButton(onClick = { transfer.find(address) }, enabled = address.isNotBlank() && !state.busy) {
							Text(stringResource(R.string.lan_find))
						}
					}
				}
			}
		},
		confirmButton = {
			if (offer != null) {
				TextButton(onClick = { transfer.answer(true) }) { Text(stringResource(R.string.lan_accept)) }
			} else {
				TextButton(onClick = transfer::close) { Text(stringResource(if (state.finished) R.string.confirm else R.string.cancel)) }
			}
		},
		dismissButton = {
			if (offer != null) TextButton(onClick = { transfer.answer(false) }) { Text(stringResource(R.string.lan_decline)) }
		},
	)
}
