package rs.clash.android.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.ramcosta.composedestinations.generated.NavGraphs
import com.ramcosta.composedestinations.generated.destinations.HomeScreenDestination
import com.ramcosta.composedestinations.generated.destinations.PanelScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ProfileScreenDestination
import com.ramcosta.composedestinations.generated.destinations.SettingsScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.spec.DirectionDestinationSpec
import com.ramcosta.composedestinations.utils.isRouteOnBackStackAsState
import com.ramcosta.composedestinations.utils.rememberDestinationsNavigator
import rs.clash.android.R

internal enum class NavigationItem(
	val direction: DirectionDestinationSpec,
	val icon: ImageVector,
	@StringRes val label: Int,
) {
	Home(HomeScreenDestination, Icons.Outlined.Home, R.string.home_screen),
	Panel(PanelScreenDestination, Icons.Outlined.Dashboard, R.string.panel_screen),
	Profile(ProfileScreenDestination, Icons.AutoMirrored.Outlined.TextSnippet, R.string.profile_screen),
	Settings(SettingsScreenDestination, Icons.Outlined.Settings, R.string.settings_screen),
}

@Composable
internal fun ClashNavigationScaffold(
	navController: NavHostController,
	adaptiveInfo: WindowAdaptiveInfo,
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	val navigator = navController.rememberDestinationsNavigator()
	val selectedItems = NavigationItem.entries.filter { navController.isRouteOnBackStackAsState(it.direction).value }
	NavigationSuiteScaffold(
		modifier = modifier,
		layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo),
		navigationSuiteItems = {
			NavigationItem.entries.forEach { destination ->
				val selected = destination in selectedItems
				item(
					selected = selected,
					onClick = { navigator.select(destination, selected) },
					icon = { Icon(destination.icon, contentDescription = null) },
					label = { Text(stringResource(destination.label)) },
				)
			}
		},
		content = content,
	)
}

private fun DestinationsNavigator.select(
	item: NavigationItem,
	selected: Boolean,
) {
	if (selected) {
		popBackStack(item.direction, false)
	} else {
		navigate(item.direction) {
			popUpTo(NavGraphs.root) { saveState = true }
			launchSingleTop = true
			restoreState = true
		}
	}
}
