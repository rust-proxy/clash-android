package rs.clash.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import rs.clash.android.R
import rs.clash.android.data.ProxyInfo
import rs.clash.android.ui.snackbar.SnackbarController.Companion.showMessage
import rs.clash.android.viewmodel.PanelAction
import rs.clash.android.viewmodel.PanelUiState
import rs.clash.android.viewmodel.PanelViewModel
import rs.clash.android.viewmodel.ProxyDelay

// Constants
private const val DELAY_EXCELLENT_MS = 300
private const val DELAY_GOOD_MS = 600

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelScreen(
	windowSizeClass: WindowSizeClass,
	navigator: DestinationsNavigator,
	modifier: Modifier = Modifier,
	viewModel: PanelViewModel = viewModel(),
) {
	val uiState by viewModel.uiState.collectAsStateWithLifecycle()
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current
	LaunchedEffect(viewModel, lifecycleOwner) {
		lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
			launch { viewModel.observe() }
			launch {
				viewModel.effects.collect { showMessage(context.getString(it.resource, it.detail)) }
			}
		}
	}

	Scaffold(
		modifier = modifier,
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.panel_screen)) },
				actions = {
					IconButton(onClick = { viewModel.onAction(PanelAction.Refresh) }) {
						Icon(
							imageVector = Icons.Default.Refresh,
							contentDescription = stringResource(R.string.refresh),
						)
					}
				},
				windowInsets = WindowInsets(),
			)
		},
	) { padding ->
		ProxyTab(
			state = uiState,
			columns =
				when {
					windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND) -> 4
					windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> 3
					windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> 2
					else -> 1
				},
			onSelectProxy = { groupName, proxyName -> viewModel.onAction(PanelAction.SelectProxy(groupName, proxyName)) },
			onTestGroupDelay = { group -> viewModel.onAction(PanelAction.TestGroupDelay(group)) },
			modifier = Modifier.padding(padding).fillMaxSize(),
		)
	}
}

@Composable
private fun ProxyTab(
	state: PanelUiState,
	columns: Int,
	onSelectProxy: (String, String) -> Unit,
	onTestGroupDelay: (String) -> Unit,
	modifier: Modifier = Modifier,
) {
	val groups = remember(state.proxies) { state.groups }
	val isLoading = state.isLoading
	val delays = state.delays
	Box(modifier = modifier.fillMaxSize()) {
		when {
			groups.isEmpty() && !isLoading -> {
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center,
				) {
					Column(
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = Arrangement.spacedBy(16.dp),
					) {
						Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
							Icon(Icons.Outlined.Hub, contentDescription = null, modifier = Modifier.padding(18.dp).size(32.dp))
						}
						Text(
							text = stringResource(R.string.no_proxy_groups),
							modifier = Modifier.padding(horizontal = 24.dp),
							textAlign = TextAlign.Center,
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}
			else -> {
				val proxyTypesMap = remember(state.proxies) { state.proxyTypes }

				if (groups.isNotEmpty()) {
					LazyColumn(
						modifier = Modifier.fillMaxSize(),
						contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
						verticalArrangement = Arrangement.spacedBy(12.dp),
					) {
						items(
							items = groups,
							key = { it.name },
						) { it ->
							ProxyGroupWidget(
								name = it.name,
								columns = columns,
								proxy = it,
								delays = delays,
								proxyTypes = proxyTypesMap,
								onSelect = { selectedName ->
									onSelectProxy(it.name, selectedName)
								},
								onTestDelay = {
									onTestGroupDelay(it.name)
								},
							)
						}
					}
				}
			}
		}

		// Loading indicator overlay
		if (isLoading) {
			Card(
				modifier =
					Modifier
						.align(Alignment.TopCenter)
						.padding(16.dp),
				elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
			) {
				Row(
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					CircularProgressIndicator(
						modifier = Modifier.size(24.dp),
						strokeWidth = 2.dp,
					)
					Text(
						text = stringResource(R.string.refreshing),
						style = MaterialTheme.typography.bodyMedium,
					)
				}
			}
		}
	}
}

@Composable
private fun ProxyGroupWidget(
	columns: Int,
	name: String,
	proxy: ProxyInfo,
	delays: Map<String, ProxyDelay>,
	proxyTypes: Map<String, String>,
	onSelect: (String) -> Unit,
	modifier: Modifier = Modifier,
	onTestDelay: () -> Unit = {},
) {
	var expanded by rememberSaveable { mutableStateOf(false) }
	val rotation by animateFloatAsState(
		targetValue = if (expanded) 180f else 0f,
		animationSpec =
			spring(
				dampingRatio = Spring.DampingRatioMediumBouncy,
				stiffness = Spring.StiffnessMedium,
			),
		label = "rotation",
	)

	Card(
		modifier = modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.large,
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
	) {
		Column(modifier = Modifier.padding(12.dp)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Row(
					modifier =
						Modifier
							.weight(1f)
							.clickable { expanded = !expanded }
							.padding(vertical = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					val noneText = stringResource(R.string.none)
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = name,
							style = MaterialTheme.typography.titleMedium,
							fontWeight = FontWeight.SemiBold,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						Spacer(modifier = Modifier.height(2.dp))
						Text(
							text = proxy.now ?: noneText,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.primary,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
					Icon(
						imageVector = Icons.Default.ExpandMore,
						contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
						modifier =
							Modifier
								.rotate(rotation)
								.padding(horizontal = 8.dp),
						tint = MaterialTheme.colorScheme.outline,
					)
				}

				IconButton(
					onClick = onTestDelay,
					modifier = Modifier.size(48.dp),
				) {
					Icon(
						imageVector = Icons.Default.Bolt,
						contentDescription = stringResource(R.string.test_latency),
						tint = MaterialTheme.colorScheme.secondary,
						modifier = Modifier.size(20.dp),
					)
				}
			}

			AnimatedVisibility(
				visible = expanded && proxy.all.isNotEmpty(),
				enter =
					expandVertically(
						animationSpec =
							spring(
								dampingRatio = Spring.DampingRatioNoBouncy,
								stiffness = Spring.StiffnessMediumLow,
							),
					) +
						fadeIn(
							animationSpec = tween(durationMillis = 300),
						),
				exit =
					shrinkVertically(
						animationSpec = tween(durationMillis = 250),
					) +
						fadeOut(
							animationSpec = tween(durationMillis = 200),
						),
			) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					HorizontalDivider()

					proxy.all.chunked(columns).forEach { rowItems ->
						Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							rowItems.forEach { option ->
								ProxyItem(
									option = option,
									isSelected = option == proxy.now,
									delay = delays[option],
									type = proxyTypes[option],
									onSelect = { onSelect(option) },
									modifier = Modifier.weight(1f),
								)
							}
							repeat(columns - rowItems.size) {
								Spacer(modifier = Modifier.weight(1f))
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun ProxyItem(
	option: String,
	isSelected: Boolean,
	delay: ProxyDelay?,
	type: String?,
	onSelect: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		selected = isSelected,
		onClick = onSelect,
		modifier = modifier,
		color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
		contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
		shape = MaterialTheme.shapes.medium,
	) {
		Column(
			modifier = Modifier.padding(12.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				Text(
					text = option,
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.weight(1f),
					fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				if (isSelected) {
					Icon(
						Icons.Default.Check,
						contentDescription = stringResource(R.string.selected),
						modifier = Modifier.size(16.dp),
					)
				}
			}

			Spacer(modifier = Modifier.height(4.dp))

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (type != null) {
					Text(
						text = type.uppercase(),
						style = MaterialTheme.typography.labelSmall,
						color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
					)
				}
				if (delay != null) {
					val testingText = stringResource(R.string.testing)
					val timeoutText = stringResource(R.string.timeout)
					val displayedDelay =
						when (delay) {
							ProxyDelay.Testing -> testingText
							ProxyDelay.Timeout -> timeoutText
							is ProxyDelay.Measured -> "${delay.millis}ms"
						}
					val delayColor =
						when {
							delay is ProxyDelay.Measured -> {
								val ms = delay.millis
								when {
									ms < DELAY_EXCELLENT_MS -> MaterialTheme.colorScheme.primary
									ms < DELAY_GOOD_MS -> MaterialTheme.colorScheme.tertiary
									else -> MaterialTheme.colorScheme.error
								}
							}
							delay == ProxyDelay.Testing -> MaterialTheme.colorScheme.outline
							else -> MaterialTheme.colorScheme.error
						}

					Text(
						text = displayedDelay,
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Medium,
						color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else delayColor,
					)
				}
			}
		}
	}
}
