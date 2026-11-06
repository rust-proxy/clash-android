package rs.clash.android.ui.snackbar

data class SnackbarAction(
	val title: String,
	val onActionPress: () -> Unit,
)
