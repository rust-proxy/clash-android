package rs.clash.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.AppSelectorScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import rs.clash.android.MainActivity
import rs.clash.android.R
import rs.clash.android.model.MAX_UI_SCALE
import rs.clash.android.model.MIN_UI_SCALE
import rs.clash.android.model.UI_SCALE_STEP
import rs.clash.android.model.UiScale
import rs.clash.android.ui.components.TextInfoDialog
import rs.clash.android.util.PermissionHelper
import rs.clash.android.viewmodel.AppFilterMode
import rs.clash.android.viewmodel.DarkModePreference
import rs.clash.android.viewmodel.LanguagePreference
import rs.clash.android.viewmodel.SettingsViewModel
import kotlin.math.roundToInt

private val DarkModePreference.labelRes: Int
	get() =
		when (this) {
			DarkModePreference.SYSTEM -> R.string.dark_mode_system
			DarkModePreference.LIGHT -> R.string.dark_mode_light
			DarkModePreference.DARK -> R.string.dark_mode_dark
		}

private val LanguagePreference.labelRes: Int
	get() =
		when (this) {
			LanguagePreference.SYSTEM -> R.string.language_system
			LanguagePreference.SIMPLIFIED_CHINESE -> R.string.language_simplified_chinese
			LanguagePreference.ENGLISH -> R.string.language_english
		}

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun SettingsScreen(
	navigator: DestinationsNavigator,
	modifier: Modifier = Modifier,
	viewModel: SettingsViewModel = viewModel(),
) {
	var showScaleDialog by rememberSaveable { mutableStateOf(false) }
	var showDarkModeDialog by remember { mutableStateOf(false) }
	var showLanguageDialog by remember { mutableStateOf(false) }
	val context = LocalContext.current
	val activity = context as? MainActivity
	val scale = UiScale(activity?.recommendedScale ?: 100, viewModel.manualUiScale)
	var hasNotificationPermission by remember {
		mutableStateOf(PermissionHelper.hasNotificationPermission(context))
	}

	val notificationPermissionLauncher =
		rememberLauncherForActivityResult(
			contract = ActivityResultContracts.RequestPermission(),
		) { isGranted ->
			hasNotificationPermission = isGranted
		}
	var showInfoDialog by remember { mutableStateOf(false) }

	// Info dialog
	if (showInfoDialog) {
		TextInfoDialog(
			title = stringResource(R.string.about_title),
			content = stringResource(R.string.known_issues_settings),
			onDismiss = { showInfoDialog = false },
		)
	}

	Scaffold(
		modifier = modifier,
		topBar = {
			TopAppBar(
				title = {
					Text(stringResource(R.string.settings_title))
				},
				windowInsets = WindowInsets(),
				actions = {
					IconButton(onClick = { showInfoDialog = true }) {
						Icon(
							imageVector = Icons.Filled.Info,
							contentDescription = stringResource(R.string.action_about),
						)
					}
				},
			)
		},
	) { padding ->
		LazyColumn(
			modifier =
				Modifier
					.padding(padding)
					.fillMaxSize(),
			contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			// Appearance Section
			item {
				SectionHeader(text = stringResource(R.string.settings_appearance))
			}

			item {
				SettingsCard {
					SettingsItem(
						icon = Icons.Default.DarkMode,
						title = stringResource(R.string.settings_dark_mode),
						subtitle = stringResource(viewModel.darkModePreference.labelRes),
						onClick = {
							showDarkModeDialog = true
						},
					)
					HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
					SettingsItem(
						icon = Icons.Default.DisplaySettings,
						title = stringResource(R.string.settings_ui_scale),
						subtitle =
							if (scale.manual == null) {
								stringResource(R.string.ui_scale_auto, scale.recommended)
							} else {
								stringResource(R.string.ui_scale_percent, scale.percent)
							},
						onClick = { showScaleDialog = true },
					)
					HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
					SettingsItem(
						icon = Icons.Default.Language,
						title = stringResource(R.string.settings_language),
						subtitle = stringResource(viewModel.languagePreference.labelRes),
						onClick = {
							showLanguageDialog = true
						},
					)
				}
			}

			// General Section
			item {
				SectionHeader(text = stringResource(R.string.settings_general))
			}

			item {
				SettingsCard {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
						!hasNotificationPermission
					) {
						SettingsItem(
							icon = Icons.Default.Notifications,
							title = stringResource(R.string.settings_notification_permission),
							subtitle = stringResource(R.string.settings_notification_permission_required),
							onClick = {
								// Request notification permission
								notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
							},
							showChevron = true,
						)
						HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
					}

					SettingsSwitchItem(
						icon = Icons.Default.Dns,
						title = stringResource(R.string.settings_fake_ip),
						subtitle = stringResource(R.string.settings_fake_ip_desc),
						checked = viewModel.fakeIpEnabled,
						onCheckedChange = { enabled ->
							viewModel.updateFakeIpEnabled(enabled)
						},
					)

					HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
					SettingsSwitchItem(
						icon = Icons.Default.Router,
						title = stringResource(R.string.settings_ipv6),
						subtitle = stringResource(R.string.settings_ipv6_desc),
						checked = viewModel.ipv6Enabled,
						onCheckedChange = { enabled ->
							viewModel.updateIpv6Enabled(enabled)
						},
					)

					HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
					SettingsItem(
						icon = Icons.Default.FilterList,
						title = stringResource(R.string.settings_app_filter),
						subtitle =
							when (viewModel.appFilterMode) {
								AppFilterMode.ALL -> stringResource(R.string.app_selector_mode_all)
								AppFilterMode.ALLOWED -> stringResource(R.string.app_selector_selected, viewModel.allowedApps.size)
								AppFilterMode.DISALLOWED -> stringResource(R.string.app_selector_selected, viewModel.disallowedApps.size)
							},
						onClick = {
							navigator.navigate(AppSelectorScreenDestination)
						},
					)
				}
			}

			// About Section
			item {
				SectionHeader(text = stringResource(R.string.settings_about))
			}

			item {
				SettingsCard {
					SettingsItem(
						icon = Icons.Default.Info,
						title = stringResource(R.string.settings_version),
						subtitle = stringResource(R.string.app_ver),
						onClick = {},
						showChevron = false,
					)
				}
			}
		}

		if (showScaleDialog) {
			UiScaleDialog(
				scale = scale,
				onDismiss = { showScaleDialog = false },
				onConfirm = {
					viewModel.updateUiScale(it)
					showScaleDialog = false
					if ((it ?: scale.recommended) != scale.percent) activity?.recreate()
				},
			)
		}

		// Dark Mode Dialog
		if (showDarkModeDialog) {
			DarkModeDialog(
				currentPreference = viewModel.darkModePreference,
				onDismiss = { showDarkModeDialog = false },
				onConfirm = { preference ->
					viewModel.updateDarkModePreference(preference)
					showDarkModeDialog = false
				},
			)
		}

		// Language Dialog
		if (showLanguageDialog) {
			LanguageDialog(
				currentPreference = viewModel.languagePreference,
				onDismiss = { showLanguageDialog = false },
				onConfirm = { preference ->
					val oldPreference = viewModel.languagePreference
					viewModel.updateLanguagePreference(preference)
					showLanguageDialog = false

					// Recreate activity if language actually changed to apply new locale
					if (oldPreference != preference) {
						activity?.recreate()
					}
				},
			)
		}
	}
}

@Composable
private fun SectionHeader(
	text: String,
	modifier: Modifier = Modifier,
) {
	Text(
		text = text,
		style = MaterialTheme.typography.titleMedium,
		color = MaterialTheme.colorScheme.primary,
		fontWeight = FontWeight.SemiBold,
		modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
	)
}

@Composable
private fun SettingsCard(
	modifier: Modifier = Modifier,
	content: @Composable () -> Unit,
) {
	Card(
		modifier = modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.large,
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
	) {
		content()
	}
}

@Composable
private fun SettingsItem(
	icon: ImageVector,
	title: String,
	subtitle: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	showChevron: Boolean = true,
) {
	Row(
		modifier =
			modifier
				.fillMaxWidth()
				.clickable(onClick = onClick)
				.padding(16.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			modifier = Modifier.weight(1f),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
			)
			Column {
				Text(
					text = title,
					style = MaterialTheme.typography.bodyLarge,
					fontWeight = FontWeight.Medium,
				)
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		if (showChevron) {
			Icon(
				imageVector = Icons.Default.ChevronRight,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun SettingsSwitchItem(
	icon: ImageVector,
	title: String,
	subtitle: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier =
			modifier
				.fillMaxWidth()
				.padding(16.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			modifier = Modifier.weight(1f),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			Icon(
				imageVector = icon,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
			)
			Column {
				Text(
					text = title,
					style = MaterialTheme.typography.bodyLarge,
					fontWeight = FontWeight.Medium,
				)
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		Switch(
			checked = checked,
			onCheckedChange = onCheckedChange,
		)
	}
}

@Composable
private fun UiScaleDialog(
	scale: UiScale,
	onDismiss: () -> Unit,
	onConfirm: (Int?) -> Unit,
) {
	var automatic by rememberSaveable { mutableStateOf(scale.manual == null) }
	var percent by rememberSaveable { mutableStateOf(scale.percent) }
	val title = stringResource(R.string.settings_ui_scale)
	val percentage = stringResource(R.string.ui_scale_percent, percent)
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				Text(
					text = stringResource(R.string.ui_scale_recommendation),
					style = MaterialTheme.typography.bodyMedium,
					modifier = Modifier.padding(bottom = 8.dp),
				)
				Column(modifier = Modifier.selectableGroup()) {
					PreferenceOption(
						text = stringResource(R.string.ui_scale_auto, scale.recommended),
						selected = automatic,
						onClick = { automatic = true },
					)
					PreferenceOption(
						text = stringResource(R.string.ui_scale_manual, percent),
						selected = !automatic,
						onClick = { automatic = false },
					)
				}
				Slider(
					value = percent.toFloat(),
					onValueChange = { percent = (it / UI_SCALE_STEP).roundToInt() * UI_SCALE_STEP },
					modifier =
						Modifier.semantics {
							contentDescription = title
							stateDescription = percentage
						},
					enabled = !automatic,
					valueRange = MIN_UI_SCALE.toFloat()..MAX_UI_SCALE.toFloat(),
					steps = (MAX_UI_SCALE - MIN_UI_SCALE) / UI_SCALE_STEP - 1,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(if (automatic) null else percent) }) {
				Text(stringResource(R.string.confirm))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
		},
	)
}

@Composable
private fun DarkModeDialog(
	currentPreference: DarkModePreference,
	onDismiss: () -> Unit,
	onConfirm: (DarkModePreference) -> Unit,
) {
	var selectedPreference by remember { mutableStateOf(currentPreference) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.settings_dark_mode)) },
		text = {
			Column(modifier = Modifier.selectableGroup()) {
				PreferenceOption(
					text = stringResource(DarkModePreference.SYSTEM.labelRes),
					selected = selectedPreference == DarkModePreference.SYSTEM,
					onClick = { selectedPreference = DarkModePreference.SYSTEM },
				)
				PreferenceOption(
					text = stringResource(DarkModePreference.LIGHT.labelRes),
					selected = selectedPreference == DarkModePreference.LIGHT,
					onClick = { selectedPreference = DarkModePreference.LIGHT },
				)
				PreferenceOption(
					text = stringResource(DarkModePreference.DARK.labelRes),
					selected = selectedPreference == DarkModePreference.DARK,
					onClick = { selectedPreference = DarkModePreference.DARK },
				)
			}
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(selectedPreference) }) {
				Text(stringResource(android.R.string.ok))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(android.R.string.cancel))
			}
		},
	)
}

@Composable
private fun PreferenceOption(
	text: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier =
			Modifier
				.fillMaxWidth()
				.selectable(
					selected = selected,
					onClick = onClick,
					role = Role.RadioButton,
				).padding(vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(
			selected = selected,
			onClick = null,
		)
		Text(
			text = text,
			modifier = Modifier.padding(start = 16.dp),
			style = MaterialTheme.typography.bodyLarge,
		)
	}
}

@Composable
private fun LanguageDialog(
	currentPreference: LanguagePreference,
	onDismiss: () -> Unit,
	onConfirm: (LanguagePreference) -> Unit,
) {
	var selectedPreference by remember { mutableStateOf(currentPreference) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.settings_language)) },
		text = {
			Column(modifier = Modifier.selectableGroup()) {
				LanguageOption(
					text = stringResource(LanguagePreference.SYSTEM.labelRes),
					selected = selectedPreference == LanguagePreference.SYSTEM,
					onClick = { selectedPreference = LanguagePreference.SYSTEM },
				)
				LanguageOption(
					text = stringResource(LanguagePreference.SIMPLIFIED_CHINESE.labelRes),
					selected = selectedPreference == LanguagePreference.SIMPLIFIED_CHINESE,
					onClick = { selectedPreference = LanguagePreference.SIMPLIFIED_CHINESE },
				)
				LanguageOption(
					text = stringResource(LanguagePreference.ENGLISH.labelRes),
					selected = selectedPreference == LanguagePreference.ENGLISH,
					onClick = { selectedPreference = LanguagePreference.ENGLISH },
				)
			}
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(selectedPreference) }) {
				Text(stringResource(android.R.string.ok))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(android.R.string.cancel))
			}
		},
	)
}

@Composable
private fun LanguageOption(
	text: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier =
			Modifier
				.fillMaxWidth()
				.selectable(
					selected = selected,
					onClick = onClick,
					role = Role.RadioButton,
				).padding(vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		RadioButton(
			selected = selected,
			onClick = null,
		)
		Text(
			text = text,
			modifier = Modifier.padding(start = 16.dp),
			style = MaterialTheme.typography.bodyLarge,
		)
	}
}
