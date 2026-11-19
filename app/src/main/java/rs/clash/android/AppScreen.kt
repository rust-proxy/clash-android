package rs.clash.android

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.generated.NavGraphs
import com.ramcosta.composedestinations.generated.destinations.ConnectionsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.HomeScreenDestination
import com.ramcosta.composedestinations.generated.destinations.PanelScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ProfileScreenDestination
import com.ramcosta.composedestinations.generated.destinations.SettingsScreenDestination
import com.ramcosta.composedestinations.navigation.dependency
import com.ramcosta.composedestinations.rememberNavHostEngine
import rs.clash.android.theme.ScaleTransitions
import rs.clash.android.theme.SlideHorizontalTransitions
import rs.clash.android.ui.components.ClashNavigationScaffold
import rs.clash.android.ui.snackbar.SnackbarControllerProvider

@Composable
fun ClashApp(modifier: Modifier = Modifier) {
	val engine = rememberNavHostEngine()
	val navCtrl = engine.rememberNavController()

	val adaptiveInfo = currentWindowAdaptiveInfoV2()
	val windowSizeClass by rememberUpdatedState(adaptiveInfo.windowSizeClass)
	ClashNavigationScaffold(
		navController = navCtrl,
		adaptiveInfo = adaptiveInfo,
		modifier = modifier.fillMaxSize(),
	) {
		SnackbarControllerProvider { snackbarHostState ->
			Scaffold(
				snackbarHost = {
					SnackbarHost(hostState = snackbarHostState) { data ->
						Snackbar(snackbarData = data)
					}
				},
			) { innerPadding ->
				DestinationsNavHost(
					engine = engine,
					navController = navCtrl,
					navGraph = NavGraphs.root,
					dependenciesContainerBuilder = { dependency(windowSizeClass) },
					modifier =
						Modifier
							.fillMaxSize()
							.padding(innerPadding)
							.consumeWindowInsets(innerPadding)
							.wrapContentWidth()
							.widthIn(max = 840.dp)
							.fillMaxWidth(),
				) {
					HomeScreenDestination animateWith SlideHorizontalTransitions
					PanelScreenDestination animateWith SlideHorizontalTransitions
					ProfileScreenDestination animateWith SlideHorizontalTransitions
					SettingsScreenDestination animateWith SlideHorizontalTransitions
					ConnectionsScreenDestination animateWith ScaleTransitions
				}
			}
		}
	}
}
