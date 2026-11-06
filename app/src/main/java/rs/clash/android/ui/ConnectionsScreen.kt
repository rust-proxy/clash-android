package rs.clash.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.delay
import rs.clash.android.R
import rs.clash.android.formatSize
import rs.clash.android.ui.components.ConnectionItem
import rs.clash.android.viewmodel.ConnectionsViewModel
import uniffi.clash_android_ffi.Connection

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun ConnectionsScreen(
	navigator: DestinationsNavigator,
	modifier: Modifier = Modifier,
	viewModel: ConnectionsViewModel = viewModel(),
) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val lifecycleOwner = LocalLifecycleOwner.current
	LaunchedEffect(viewModel, lifecycleOwner) {
		lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
			viewModel.observe()
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				navigationIcon = {
					IconButton(onClick = { navigator.navigateUp() }) {
						Icon(
							imageVector = Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(R.string.back),
						)
					}
				},
				title = {
					Text(stringResource(R.string.connections_title))
				},
				windowInsets = WindowInsets(0, 0, 0, 0),
			)
		},
	) { padding ->
		ConnectionsContent(
			connections = uiState.connections,
			downloadTotal = uiState.downloadTotal,
			uploadTotal = uiState.uploadTotal,
			errorMessage = uiState.errorMessage,
			modifier = Modifier.padding(padding).fillMaxSize(),
		)
	}
}

@Composable
fun ConnectionsContent(
	connections: List<Connection>,
	downloadTotal: Long,
	uploadTotal: Long,
	errorMessage: String?,
	modifier: Modifier = Modifier,
) {
	LazyColumn(
		modifier =
			modifier
				.fillMaxSize()
				.padding(horizontal = 16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		// Summary Card
		item(key = "summary") {
			Card(
				modifier = Modifier.fillMaxWidth(),
				elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
			) {
				Column(
					modifier = Modifier.padding(16.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					Text(
						text = stringResource(R.string.connections_summary),
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
					)
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Column {
							Text(
								text = stringResource(R.string.connections_count),
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.secondary,
							)
							Text(
								text = "${connections.size}",
								style = MaterialTheme.typography.titleLarge,
								fontWeight = FontWeight.Bold,
								color = MaterialTheme.colorScheme.primary,
							)
						}
						Column(horizontalAlignment = Alignment.End) {
							Text(
								text = stringResource(R.string.stat_download),
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.secondary,
							)
							Text(
								text = formatSize(downloadTotal),
								style = MaterialTheme.typography.titleLarge,
								fontWeight = FontWeight.Bold,
								color = MaterialTheme.colorScheme.primary,
							)
						}
						Column(horizontalAlignment = Alignment.End) {
							Text(
								text = stringResource(R.string.stat_upload),
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.secondary,
							)
							Text(
								text = formatSize(uploadTotal),
								style = MaterialTheme.typography.titleLarge,
								fontWeight = FontWeight.Bold,
								color = MaterialTheme.colorScheme.primary,
							)
						}
					}
				}
			}
		}

		// Error message
		if (errorMessage != null) {
			item(key = "error") {
				Card(
					modifier = Modifier.fillMaxWidth(),
					colors =
						CardDefaults.cardColors(
							containerColor = MaterialTheme.colorScheme.errorContainer,
						),
				) {
					Text(
						text = errorMessage,
						modifier = Modifier.padding(16.dp),
						color = MaterialTheme.colorScheme.onErrorContainer,
					)
				}
			}
		}

		// Empty state
		if (connections.isEmpty() && errorMessage == null) {
			item(key = "empty") {
				Box(
					modifier = Modifier.fillMaxWidth().padding(32.dp),
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = stringResource(R.string.connections_empty),
						style = MaterialTheme.typography.bodyLarge,
						color = MaterialTheme.colorScheme.outline,
					)
				}
			}
		}

		// Connection items
		itemsIndexed(
			items = connections,
			key = { _, connection -> connection.id },
		) { index, connection ->
			var visible by remember { mutableStateOf(false) }
			
			LaunchedEffect(connection.id) {
				delay(index * 30L)
				visible = true
			}
			
			AnimatedVisibility(
				visible = visible,
				enter =
					slideInVertically(
						initialOffsetY = { it / 4 },
						animationSpec =
							spring(
								dampingRatio = Spring.DampingRatioMediumBouncy,
								stiffness = Spring.StiffnessMediumLow,
							),
					) +
						fadeIn(
							animationSpec = tween(durationMillis = 300),
						),
				exit = fadeOut(animationSpec = tween(durationMillis = 200)),
			) {
				ConnectionItem(connection = connection)
			}
		}
	}
}
