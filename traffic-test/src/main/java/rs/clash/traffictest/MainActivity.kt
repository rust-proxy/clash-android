package rs.clash.traffictest

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
				ProbeScreen()
			}
		}
	}
}

@Composable
private fun ProbeScreen(model: ProbeViewModel = viewModel()) {
	val focus = LocalFocusManager.current
	val context = LocalContext.current
	val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.run() }
	Scaffold { insets ->
		Column(
			Modifier
				.padding(insets)
				.imePadding()
				.verticalScroll(rememberScrollState())
				.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedTextField(
					value = model.host,
					onValueChange = {
						model.host = it
						model.results.clear()
					},
					modifier = Modifier.weight(2f),
					label = { Text(stringResource(R.string.echo_address)) },
					enabled = !model.running,
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
				)
				OutlinedTextField(
					value = model.port,
					onValueChange = {
						model.port = it
						model.results.clear()
					},
					modifier = Modifier.weight(1f),
					label = { Text(stringResource(R.string.port)) },
					enabled = !model.running,
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
				)
			}
			OutlinedTextField(
				value = model.domain,
				onValueChange = {
					model.domain = it
					model.results.clear()
				},
				modifier = Modifier.fillMaxWidth(),
				label = { Text(stringResource(R.string.dns_name)) },
				enabled = !model.running,
				singleLine = true,
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
			)
			Button(
				onClick = {
					focus.clearFocus()
					if (Build.VERSION.SDK_INT >= 37 &&
						context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
					) {
						permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
					} else {
						model.run()
					}
				},
				modifier = Modifier.fillMaxWidth(),
				enabled = !model.running,
			) { Text(stringResource(if (model.running) R.string.running else R.string.run_all)) }
			for (probe in Probe.entries) {
				val result = model.results[probe]
				Card(Modifier.fillMaxWidth()) {
					Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
						Text(stringResource(probe.title), style = MaterialTheme.typography.titleMedium)
						if (result == null) {
							Text(stringResource(if (model.running) R.string.running else R.string.pending))
						} else {
							Text(
								stringResource(if (result.passed) R.string.passed else R.string.failed, result.elapsed),
								color = if (result.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
							)
							Text(result.detail)
						}
					}
				}
			}
		}
	}
}
