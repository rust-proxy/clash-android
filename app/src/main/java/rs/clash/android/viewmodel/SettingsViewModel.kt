package rs.clash.android.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import rs.clash.android.R

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

	var languagePreference: LanguagePreference by mutableStateOf(loadLanguagePreference())
		private set

	var foregroundServiceEnabled: Boolean by mutableStateOf(loadForegroundServiceEnabled())
		private set

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
	// the file changes keeps `getAppFilterSummary()` from showing a stale count.
	// The listener is deliberately stored in a field: SharedPreferences keeps registered
	// listeners only weakly, so an inline listener could be collected and never called.
	private val preferenceChangeListener =
		SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
			when (key) {
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

	private fun loadForegroundServiceEnabled(): Boolean = prefs.getBoolean("foreground_service_enabled", false)

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

	fun updateLanguagePreference(preference: LanguagePreference) {
		languagePreference = preference
		prefs.edit { putString("language", preference.name) }
	}

	fun updateForegroundServiceEnabled(enabled: Boolean) {
		foregroundServiceEnabled = enabled
		prefs.edit { putBoolean("foreground_service_enabled", enabled) }
	}

	fun updateFakeIpEnabled(enabled: Boolean) {
		fakeIpEnabled = enabled
		prefs.edit { putBoolean("fake_ip", enabled) }
	}

	fun updateIpv6Enabled(enabled: Boolean) {
		ipv6Enabled = enabled
		prefs.edit { putBoolean("ipv6", enabled) }
	}

	fun updateAppFilterMode(mode: AppFilterMode) {
		appFilterMode = mode
		prefs.edit { putString("app_filter_mode", mode.name) }
	}

	fun updateAllowedApps(apps: Set<String>) {
		allowedApps = apps
		prefs.edit { putStringSet("allowed_apps", apps) }
	}

	fun updateDisallowedApps(apps: Set<String>) {
		disallowedApps = apps
		prefs.edit { putStringSet("disallowed_apps", apps) }
	}

	fun getDarkModeDisplayName(): String {
		val context = getApplication<Application>().applicationContext
		return when (darkModePreference) {
			DarkModePreference.SYSTEM -> context.getString(R.string.dark_mode_system)
			DarkModePreference.LIGHT -> context.getString(R.string.dark_mode_light)
			DarkModePreference.DARK -> context.getString(R.string.dark_mode_dark)
		}
	}

	fun getLanguageDisplayName(): String {
		val context = getApplication<Application>().applicationContext
		return when (languagePreference) {
			LanguagePreference.SYSTEM -> context.getString(R.string.language_system)
			LanguagePreference.SIMPLIFIED_CHINESE -> context.getString(R.string.language_simplified_chinese)
			LanguagePreference.ENGLISH -> context.getString(R.string.language_english)
		}
	}

	fun getAppFilterModeDisplayName(): String {
		val context = getApplication<Application>().applicationContext
		return when (appFilterMode) {
			AppFilterMode.ALL -> context.getString(R.string.app_selector_mode_all)
			AppFilterMode.ALLOWED -> context.getString(R.string.app_selector_mode_allowed)
			AppFilterMode.DISALLOWED -> context.getString(R.string.app_selector_mode_disallowed)
		}
	}

	fun getAppFilterSummary(): String {
		val context = getApplication<Application>().applicationContext
		return when (appFilterMode) {
			AppFilterMode.ALL -> context.getString(R.string.app_selector_mode_all)
			AppFilterMode.ALLOWED -> context.getString(R.string.app_selector_selected, allowedApps.size)
			AppFilterMode.DISALLOWED -> context.getString(R.string.app_selector_selected, disallowedApps.size)
		}
	}
}
