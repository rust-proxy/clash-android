package rs.clash.android.ui.snackbar

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun SnackbarControllerProvider(content: @Composable (snackbarHost: SnackbarHostState) -> Unit) {
	val snackHostState = remember { SnackbarHostState() }
	val scope = rememberCoroutineScope()
	val snackController =
		remember(scope) {
			SnackbarController(snackHostState, scope)
		}

	DisposableEffect(snackController, scope) {
		val job =
			scope.launch {
				val channel = SnackbarController.getChannel()
				for (payload in channel) {
					snackController.showMessage(
						message = payload.message,
						duration = payload.duration,
						action = payload.action,
					)
				}
			}

		onDispose {
			job.cancel()
		}
	}

	content(snackHostState)
}
