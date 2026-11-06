package rs.clash.android.ui.snackbar

import androidx.compose.material3.SnackbarDuration

data class SnackbarChannelMessage(
	val message: String,
	val action: SnackbarAction? = null,
	val duration: SnackbarDuration = SnackbarDuration.Short,
)
