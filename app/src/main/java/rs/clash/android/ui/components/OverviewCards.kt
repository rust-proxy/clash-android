package rs.clash.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import rs.clash.android.R
import rs.clash.android.data.MemoryUsage
import rs.clash.android.formatSize

@Composable
internal fun VpnStatusCard(
	running: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val scheme = MaterialTheme.colorScheme
	val container =
		when {
			running -> scheme.primary
			scheme.surface.luminance() < 0.5f -> scheme.secondaryContainer
			else -> scheme.inverseSurface
		}
	val ink = contentColorFor(container)
	Surface(
		onClick = onClick,
		modifier = modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.extraLarge,
		color = container,
		contentColor = ink,
	) {
		Row(
			modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
			horizontalArrangement = Arrangement.spacedBy(20.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.stat_vpn),
					style = MaterialTheme.typography.labelLarge,
				)
				Spacer(Modifier.height(6.dp))
				Text(
					text = stringResource(if (running) R.string.stat_vpn_running else R.string.stat_vpn_stopped),
					style = MaterialTheme.typography.headlineLarge,
					fontWeight = FontWeight.SemiBold,
				)
				Spacer(Modifier.height(8.dp))
				Text(
					text = stringResource(if (running) R.string.stat_vpn_hint_stop else R.string.stat_vpn_hint_start),
					style = MaterialTheme.typography.bodySmall,
				)
			}
			Box(
				modifier =
					Modifier
						.size(60.dp)
						.drawBehind {
							for (radius in listOf(39.dp, 52.dp, 69.dp)) {
								drawCircle(ink.copy(alpha = 0.15f), radius.toPx(), style = Stroke(1.dp.toPx()))
							}
						}.background(ink, CircleShape),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					imageVector = Icons.Outlined.PowerSettingsNew,
					contentDescription = null,
					modifier = Modifier.size(30.dp),
					tint = container,
				)
			}
		}
	}
}

@Composable
internal fun TrafficCard(
	title: String,
	value: String,
	icon: ImageVector,
	accent: Color,
	inlineValue: Boolean,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		FlowRow(
			modifier = Modifier.fillMaxWidth().padding(16.dp),
			maxItemsInEachRow = if (inlineValue) 2 else 1,
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Row(
				modifier = Modifier.align(Alignment.CenterVertically),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
				Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
		}
	}
}

@Composable
internal fun RuntimeCard(
	memory: MemoryUsage?,
	connections: Int,
	running: Boolean,
	wide: Boolean,
	onConnectionsClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		FlowRow(maxItemsInEachRow = if (wide) 2 else 1) {
			val cell = Modifier.weight(1f)
			RuntimeRow(
				icon = Icons.Outlined.DeviceHub,
				title = stringResource(R.string.stat_connections),
				detail = stringResource(if (connections > 0) R.string.stat_connections_ongoing else R.string.stat_connections_none),
				value = connections.toString(),
				modifier = cell.clickable(onClick = onConnectionsClick),
				showChevron = true,
			)
			if (!wide) {
				HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
			}
			RuntimeRow(
				icon = Icons.Outlined.Memory,
				modifier = cell,
				title = stringResource(R.string.stat_memory),
				detail =
					when {
						memory == null && running -> stringResource(R.string.refreshing)
						memory != null && memory.oslimit > 0 -> stringResource(R.string.stat_memory_limit, formatSize(memory.oslimit))
						else -> null
					},
				value = memory?.let { formatSize(it.inuse) } ?: stringResource(R.string.not_available),
			)
		}
	}
}

@Composable
private fun RuntimeRow(
	icon: ImageVector,
	title: String,
	detail: String?,
	value: String,
	modifier: Modifier = Modifier,
	showChevron: Boolean = false,
) {
	Row(
		modifier = modifier.fillMaxWidth().padding(16.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
		FlowRow(
			modifier = Modifier.weight(1f),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalArrangement = Arrangement.spacedBy(4.dp),
			itemVerticalAlignment = Alignment.CenterVertically,
		) {
			Column(modifier = Modifier.padding(end = 8.dp)) {
				Text(title, style = MaterialTheme.typography.titleSmall)
				if (detail != null) {
					Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
			Text(value, style = MaterialTheme.typography.titleMedium)
		}
		if (showChevron) {
			Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
		}
	}
}
