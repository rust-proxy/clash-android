package rs.clash.android

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.generated.NavGraphs
import com.ramcosta.composedestinations.generated.destinations.ConnectionsScreenDestination
import com.ramcosta.composedestinations.generated.destinations.HomeScreenDestination
import com.ramcosta.composedestinations.generated.destinations.PanelScreenDestination
import com.ramcosta.composedestinations.generated.destinations.ProfileScreenDestination
import com.ramcosta.composedestinations.generated.destinations.SettingsScreenDestination
import com.ramcosta.composedestinations.rememberNavHostEngine
import rs.clash.android.theme.ScaleTransitions
import rs.clash.android.theme.SlideHorizontalTransitions
import rs.clash.android.ui.components.BottomBar
import rs.clash.android.ui.snackbar.SnackbarControllerProvider

@Composable
fun ClashApp(modifier: Modifier = Modifier) {
	val engine = rememberNavHostEngine()
	val navCtrl = engine.rememberNavController()

	SnackbarControllerProvider { snackbarHostState ->
		Scaffold(
			modifier = modifier,
			bottomBar = { BottomBar(navCtrl) },
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
				modifier = Modifier.padding(innerPadding),
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
