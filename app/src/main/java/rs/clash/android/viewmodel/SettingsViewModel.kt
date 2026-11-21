package rs.clash.android.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import rs.clash.android.data.AppData
import rs.clash.android.model.UI_SCALE_KEY
import rs.clash.android.service.KernelSuService
import rs.clash.android.ui.snackbar.SnackbarController
import rs.clash.android.util.manualUiScale

enum class DarkModePreference {
	SYSTEM,
	LIGHT,
	DARK,
}

enum class LanguagePreference {
	SYSTEM,
	SIMPLIFIED_CHINESE,
	ENGLISH,
}

enum class AppFilterMode {
	ALL,
	ALLOWED,
	DISALLOWED,
}

class SettingsViewModel(
	application: Application,
) : AndroidViewModel(application) {
	private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

	var darkModePreference: DarkModePreference by mutableStateOf(loadDarkModePreference())
		private set

	internal var manualUiScale: Int? by mutableStateOf(prefs.manualUiScale())
		private set

	var languagePreference: LanguagePreference by mutableStateOf(loadLanguagePreference())
		private set

	val session get() = AppData.vpn.session

	var kernelSuEnabled by mutableStateOf(prefs.getBoolean(KernelSuService.MODE_KEY, false))
		private set

	fun selectKernelSu(enabled: Boolean) {
		if (session.value.running) return
		kernelSuEnabled = enabled
		prefs.edit { putBoolean(KernelSuService.MODE_KEY, enabled) }
	}

	var fakeIpEnabled: Boolean by mutableStateOf(loadFakeIpEnabled())
		private set

	var ipv6Enabled: Boolean by mutableStateOf(loadIpv6Enabled())
		private set

	var appFilterMode: AppFilterMode by mutableStateOf(loadAppFilterMode())
		private set

	var allowedApps: Set<String> by mutableStateOf(loadAllowedApps())
		private set

	var disallowedApps: Set<String> by mutableStateOf(loadDisallowedApps())
		private set

	// The app filter is edited on a different screen (the app selector writes the same
	// "settings" file through its own ViewModel instance), and this screen can come back
	// without its ViewModel being recreated. Re-reading the three app-filter values whenever
	// the file changes keeps the displayed count current.
	// The listener is deliberately stored in a field: SharedPreferences keeps registered
	// listeners only weakly, so an inline listener could be collected and never called.
	private val preferenceChangeListener =
		SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			when (key) {
				KernelSuService.MODE_KEY -> kernelSuEnabled = prefs.getBoolean(key, false)
				"app_filter_mode" -> appFilterMode = loadAppFilterMode()
				"allowed_apps" -> allowedApps = loadAllowedApps()
				"disallowed_apps" -> disallowedApps = loadDisallowedApps()
			}
		}

	init {
		prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
	}

	override fun onCleared() {
		super.onCleared()
		prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
	}

	private fun loadDarkModePreference(): DarkModePreference {
		val value = prefs.getString("dark_mode", "SYSTEM") ?: "SYSTEM"
		return try {
			DarkModePreference.valueOf(value)
		} catch (e: IllegalArgumentException) {
			DarkModePreference.SYSTEM
		}
	}

	private fun loadLanguagePreference(): LanguagePreference {
		val value = prefs.getString("language", "SYSTEM") ?: "SYSTEM"
		return try {
			LanguagePreference.valueOf(value)
		} catch (e: IllegalArgumentException) {
			LanguagePreference.SYSTEM
		}
	}

	private fun loadFakeIpEnabled(): Boolean = prefs.getBoolean("fake_ip", false)

	private fun loadIpv6Enabled(): Boolean = prefs.getBoolean("ipv6", true)

	private fun loadAppFilterMode(): AppFilterMode {
		val value = prefs.getString("app_filter_mode", "ALL") ?: "ALL"
		return try {
			AppFilterMode.valueOf(value)
		} catch (e: IllegalArgumentException) {
			AppFilterMode.ALL
		}
	}

	private fun loadAllowedApps(): Set<String> {
		val value = prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet()
		return value.toSet()
	}

	private fun loadDisallowedApps(): Set<String> {
		val value = prefs.getStringSet("disallowed_apps", emptySet()) ?: emptySet()
		return value.toSet()
	}

	fun updateDarkModePreference(preference: DarkModePreference) {
		darkModePreference = preference
		prefs.edit { putString("dark_mode", preference.name) }
	}

	internal fun updateUiScale(percent: Int?) {
		manualUiScale = percent
		prefs.edit {
			if (percent == null) remove(UI_SCALE_KEY) else putInt(UI_SCALE_KEY, percent)
		}
	}

	fun updateLanguagePreference(preference: LanguagePreference) {
		languagePreference = preference
		prefs.edit { putString("language", preference.name) }
	}

	fun updateFakeIpEnabled(enabled: Boolean) {
		if (fakeIpEnabled == enabled) return
		fakeIpEnabled = enabled
		prefs.edit { putBoolean("fake_ip", enabled) }
		restartProxy()
	}

	fun updateIpv6Enabled(enabled: Boolean) {
		if (ipv6Enabled == enabled) return
		ipv6Enabled = enabled
		prefs.edit { putBoolean("ipv6", enabled) }
		restartProxy()
	}

	fun saveAppFilter(
		mode: AppFilterMode,
		apps: Set<String>,
	) {
		val allowed = if (mode == AppFilterMode.ALLOWED) apps else emptySet()
		val disallowed = if (mode == AppFilterMode.DISALLOWED) apps else emptySet()
		val changed =
			mode != appFilterMode ||
				(mode == AppFilterMode.ALLOWED && allowed != allowedApps) ||
				(mode == AppFilterMode.DISALLOWED && disallowed != disallowedApps)
		prefs.edit {
			putString("app_filter_mode", mode.name)
			putStringSet("allowed_apps", allowed)
			putStringSet("disallowed_apps", disallowed)
		}
		if (changed) restartProxy()
	}

	private fun restartProxy() {
		viewModelScope.launch {
			restartVpnForConfigChange(getApplication())?.let { SnackbarController.showMessage(it) }
		}
	}
}
