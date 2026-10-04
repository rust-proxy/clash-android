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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import rs.clash.android.R
import rs.clash.android.viewmodel.HomeViewModel
import uniffi.clash_android_ffi.Proxy

// Constants
private const val DELAY_EXCELLENT_MS = 300
private const val DELAY_GOOD_MS = 600
private const val PROXY_COLUMNS = 2

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelScreen(
	navigator: DestinationsNavigator,
	modifier: Modifier = Modifier,
	viewModel: HomeViewModel = viewModel(),
) {
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.panel_screen)) },
				actions = {
					IconButton(onClick = { viewModel.fetchProxies() }) {
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
		val proxies = viewModel.proxies
		val isRefreshing = viewModel.isRefreshing
		val delays = viewModel.delays

		ProxyTab(
			proxies = proxies,
			isRefreshing = isRefreshing,
			delays = delays,
			onFetchProxies = { viewModel.fetchProxies() },
			onSelectProxy = { groupName, proxyName -> viewModel.selectProxy(groupName, proxyName) },
			onTestGroupDelay = { proxies -> viewModel.testGroupDelay(proxies) },
			modifier = Modifier.padding(padding).fillMaxSize(),
		)
	}
}

@Composable
private fun ProxyTab(
	proxies: Array<Proxy>,
	isRefreshing: Boolean,
	delays: Map<String, String>,
	onFetchProxies: () -> Unit,
	onSelectProxy: (String, String) -> Unit,
	onTestGroupDelay: (List<String>) -> Unit,
	modifier: Modifier = Modifier,
) {
	val currentOnFetchProxies by rememberUpdatedState(onFetchProxies)

	LaunchedEffect(Unit) {
		if (proxies.isEmpty()) {
			currentOnFetchProxies()
		}
	}

	Box(modifier = modifier.fillMaxSize()) {
		when {
			proxies.isEmpty() && !isRefreshing -> {
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = stringResource(R.string.no_proxy_groups),
						style = MaterialTheme.typography.bodyLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			else -> {
				val proxyTypesMap =
					remember(proxies) {
						proxies.associate { it.name to it.proxyType }
					}
				val groups =
					remember(proxies) {
						proxies.filter { it.all.isNotEmpty() }.toList()
					}

				if (groups.isNotEmpty()) {
					LazyColumn(
						modifier = Modifier.fillMaxSize(),
						verticalArrangement = Arrangement.spacedBy(12.dp),
					) {
						item { Spacer(modifier = Modifier.height(16.dp)) }

						items(
							items = groups,
							key = { it.name },
						) { it ->
							Box(modifier = Modifier.padding(horizontal = 16.dp)) {
								ProxyGroupWidget(
									name = it.name,
									proxy = it,
									delays = delays,
									proxyTypes = proxyTypesMap,
									onSelect = { selectedName ->
										onSelectProxy(it.name, selectedName)
									},
									onTestDelay = {
										// Only leaf nodes can be delay-tested; passing the
										// nested group names makes clash answer with an
										// error for every nested group.
										onTestGroupDelay(
											it.all.filter { option -> proxyTypesMap[option] != "Selector" },
										)
									},
								)
							}
						}

						item { Spacer(modifier = Modifier.height(100.dp)) } // More bottom space
					}
				}
			}
		}

		// Loading indicator overlay
		if (isRefreshing) {
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
	name: String,
	proxy: Proxy,
	delays: Map<String, String>,
	proxyTypes: Map<String, String>,
	onSelect: (String) -> Unit,
	modifier: Modifier = Modifier,
	onTestDelay: () -> Unit = {},
) {
	var expanded by remember { mutableStateOf(false) }
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
		elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
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
					modifier = Modifier.size(40.dp),
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
				Column {
					Spacer(modifier = Modifier.height(8.dp))
					HorizontalDivider()
					Spacer(modifier = Modifier.height(4.dp))

					proxy.all.chunked(PROXY_COLUMNS).forEach { rowItems ->
						Row(modifier = Modifier.fillMaxWidth()) {
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
							if (rowItems.size == 1) {
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
	delay: String?,
	type: String?,
	onSelect: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier =
			modifier
				.clickable { onSelect() }
				.padding(8.dp),
		color =
			if (isSelected) {
				MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
			} else {
				Color.Transparent
			},
		shape = MaterialTheme.shapes.small,
	) {
		Column(
			modifier = Modifier.padding(8.dp),
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
					color =
						if (isSelected) {
							MaterialTheme.colorScheme.primary
						} else {
							MaterialTheme.colorScheme.onSurface
						},
					fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				if (isSelected) {
					Icon(
						Icons.Default.Check,
						contentDescription = stringResource(R.string.selected),
						tint = MaterialTheme.colorScheme.primary,
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
						fontSize = 9.sp,
						color = MaterialTheme.colorScheme.outline,
						maxLines = 1,
					)
				}
				if (delay != null) {
					val testingText = stringResource(R.string.testing)
					val timeoutText = stringResource(R.string.timeout)
					val displayedDelay =
						when (delay) {
							"testing..." -> testingText
							"timeout" -> timeoutText
							else -> delay
						}
					val delayColor =
						when {
							delay.contains("ms") -> {
								val ms = delay.replace("ms", "").trim().toIntOrNull() ?: 0
								when {
									ms < DELAY_EXCELLENT_MS -> Color(0xFF4CAF50) // Green
									ms < DELAY_GOOD_MS -> Color(0xFFFFC107) // Amber
									else -> Color(0xFFF44336) // Red
								}
							}
							delay == "testing..." || delay == testingText -> MaterialTheme.colorScheme.outline
							else -> MaterialTheme.colorScheme.error
						}

					Text(
						text = displayedDelay,
						style = MaterialTheme.typography.labelSmall,
						fontSize = 9.sp,
						fontWeight = FontWeight.Medium,
						color = delayColor,
					)
				}
			}
		}
	}
}
