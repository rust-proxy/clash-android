package rs.clash.android.ui

import android.app.Activity.RESULT_OK
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.ConnectionsScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import rs.clash.android.MainActivity
import rs.clash.android.R
import rs.clash.android.data.MemoryUsage
import rs.clash.android.formatSize
import rs.clash.android.ui.components.RuntimeCard
import rs.clash.android.ui.components.TrafficCard
import rs.clash.android.ui.components.VpnStatusCard
import rs.clash.android.ui.snackbar.SnackbarController.Companion.showMessage
import rs.clash.android.viewmodel.HomeAction
import rs.clash.android.viewmodel.HomeEffect
import rs.clash.android.viewmodel.HomeViewModel

@Destination<RootGraph>(start = true)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
	windowSizeClass: WindowSizeClass,
	navigator: DestinationsNavigator,
	modifier: Modifier = Modifier,
	viewModel: HomeViewModel = viewModel(),
) {
	val context = LocalContext.current
	val activity = LocalActivity.current as? MainActivity
	var showActions by remember { mutableStateOf(false) }
	var showRestartDialog by remember { mutableStateOf(false) }

	val vpnPermissionLauncher =
		rememberLauncherForActivityResult(
			contract = ActivityResultContracts.StartActivityForResult(),
			onResult = { result ->
				viewModel.onAction(HomeAction.PermissionResult(result.resultCode == RESULT_OK))
			},
		)

	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val isVpnRunning = uiState.isVpnRunning
	val lifecycleOwner = LocalLifecycleOwner.current
	LaunchedEffect(viewModel, lifecycleOwner) {
		lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
			launch { viewModel.observe() }
		}
	}
	LaunchedEffect(viewModel, lifecycleOwner, vpnPermissionLauncher) {
		lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
			viewModel.effects.collect { effect ->
				when (effect) {
					is HomeEffect.RequestVpnPermission -> vpnPermissionLauncher.launch(effect.intent)
					is HomeEffect.Message ->
						showMessage(
							if (effect.detail == null) context.getString(effect.resource) else context.getString(effect.resource, effect.detail),
						)
				}
			}
		}
	}

	// Restart confirmation dialog
	if (showRestartDialog) {
		AlertDialog(
			onDismissRequest = { showRestartDialog = false },
			title = { Text(stringResource(R.string.restart_confirm_title)) },
			text = { Text(stringResource(R.string.restart_confirm_message)) },
			confirmButton = {
				TextButton(
					onClick = {
						showRestartDialog = false
						viewModel.onAction(HomeAction.RestartVpn)
					},
				) {
					Text(stringResource(R.string.confirm))
				}
			},
			dismissButton = {
				TextButton(onClick = { showRestartDialog = false }) {
					Text(stringResource(R.string.cancel))
				}
			},
		)
	}

	Scaffold(
		modifier = modifier.fillMaxSize(),
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.home_title)) },
				actions = {
					Box {
						IconButton(onClick = { showActions = true }) {
							Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
						}
						DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
							DropdownMenuItem(
								text = { Text(stringResource(R.string.action_exit_ui_keep_vpn)) },
								enabled = isVpnRunning && activity != null,
								onClick = {
									showActions = false
									activity?.exitUiKeepingVpn()
								},
							)
							DropdownMenuItem(
								text = { Text(stringResource(R.string.action_restart)) },
								enabled = isVpnRunning,
								onClick = {
									showActions = false
									showRestartDialog = true
								},
							)
						}
					}
				},
				windowInsets = WindowInsets(),
			)
		},
	) { padding ->
		val memory = uiState.overview.memory
		val connections = uiState.overview.connectionCount
		val download = uiState.overview.downloadTotal
		val upload = uiState.overview.uploadTotal

		OverviewTab(
			windowSizeClass = windowSizeClass,
			memory = memory,
			connections = connections,
			download = download,
			upload = upload,
			isVpnRunning = isVpnRunning,
			onVpnToggle = {
				viewModel.onAction(if (isVpnRunning) HomeAction.StopVpn else HomeAction.StartVpn)
			},
			onConnectionsClick = {
				navigator.navigate(ConnectionsScreenDestination)
			},
			modifier = Modifier.padding(padding).fillMaxSize(),
		)
	}
}

@Composable
fun OverviewTab(
	windowSizeClass: WindowSizeClass,
	memory: MemoryUsage?,
	connections: Int,
	download: Long,
	upload: Long,
	isVpnRunning: Boolean,
	onVpnToggle: () -> Unit,
	modifier: Modifier = Modifier,
	onConnectionsClick: () -> Unit = {},
) {
	val wide = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
	val trafficColumns = if (windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) 2 else 1
	Column(
		modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		if (wide) {
			Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
				VpnStatusCard(running = isVpnRunning, onClick = onVpnToggle, modifier = Modifier.weight(1f))
				TrafficSummary(download, upload, columns = trafficColumns, modifier = Modifier.weight(1f))
			}
		} else {
			VpnStatusCard(running = isVpnRunning, onClick = onVpnToggle)
			TrafficSummary(download, upload, columns = trafficColumns)
		}
		OverviewHeading(stringResource(R.string.runtime_details))
		RuntimeCard(
			memory = memory,
			connections = connections,
			running = isVpnRunning,
			wide = wide,
			onConnectionsClick = onConnectionsClick,
		)
	}
}

@Composable
private fun TrafficSummary(
	download: Long,
	upload: Long,
	columns: Int,
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
		OverviewHeading(stringResource(R.string.traffic_summary))
		FlowRow(
			maxItemsInEachRow = columns,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			TrafficCard(
				title = stringResource(R.string.stat_download),
				value = formatSize(download),
				icon = Icons.Default.ArrowDownward,
				accent = MaterialTheme.colorScheme.primary,
				inlineValue = columns == 1,
				modifier = Modifier.weight(1f),
			)
			TrafficCard(
				title = stringResource(R.string.stat_upload),
				value = formatSize(upload),
				icon = Icons.Default.ArrowUpward,
				accent = MaterialTheme.colorScheme.tertiary,
				inlineValue = columns == 1,
				modifier = Modifier.weight(1f),
			)
		}
	}
}

@Composable
private fun OverviewHeading(title: String) {
	Text(
		text = title,
		modifier = Modifier.padding(start = 4.dp, top = 8.dp),
		style = MaterialTheme.typography.titleMedium,
	)
}
