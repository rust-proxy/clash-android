package rs.clash.android

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.core.content.ContextCompat
import rs.clash.android.data.AppData
import rs.clash.android.diagnostics.ProxyDiagnostics
import rs.clash.android.theme.ClashAndroidTheme
import rs.clash.android.ui.AppUpdateDialog
import rs.clash.android.util.manualUiScale
import rs.clash.android.util.recommendedUiScale
import rs.clash.android.viewmodel.AppUpdateViewModel
import rs.clash.android.viewmodel.ProfileViewModel
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
	internal val updates: AppUpdateViewModel by viewModels()
	private var exitUiRequested = false
	internal var recommendedScale: Int = 100
		private set

	private val notificationPermissionLauncher =
		registerForActivityResult(
			ActivityResultContracts.RequestPermission(),
		) { isGranted: Boolean ->
			// 权限结果处理
			if (isGranted) {
				android.util.Log.i("clash", "Notification permission granted")
			} else {
				android.util.Log.i("clash", "Notification permission denied")
			}
		}

	override fun attachBaseContext(newBase: Context) {
		super.attachBaseContext(newBase)
		recommendedScale = newBase.recommendedUiScale()
		val prefs = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE)
		val percent = prefs.manualUiScale() ?: recommendedScale
		val locale =
			when (prefs.getString("language", "SYSTEM")) {
				"SIMPLIFIED_CHINESE" -> Locale.SIMPLIFIED_CHINESE
				"ENGLISH" -> Locale.ENGLISH
				else -> null
			}
		// Keep both overrides on the activity when Android updates its window configuration.
		if (percent != 100 || locale != null) {
			val overrides = Configuration()
			if (percent != 100) {
				overrides.densityDpi = (newBase.resources.configuration.densityDpi * percent / 100f).roundToInt()
			}
			if (locale != null) overrides.setLocale(locale)
			applyOverrideConfiguration(overrides)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Request notification permission (Android 13+)
		requestNotificationPermission()

		setContent {
			ClashAndroidTheme {
				val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
				LaunchedEffect(lightSurface) {
					val bars =
						if (lightSurface) {
							SystemBarStyle.light(Color.TRANSPARENT, Color.DKGRAY)
						} else {
							SystemBarStyle.dark(Color.TRANSPARENT)
						}
					enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
				}
				Surface(color = MaterialTheme.colorScheme.background) {
					ClashApp()
					if (!BuildConfig.DEBUG && updates.visible) AppUpdateDialog(updates)
				}
			}
		}
	}

	override fun onStart() {
		super.onStart()
		ProxyDiagnostics.recover(this)
		AppData.vpn.attach()
		if (!BuildConfig.DEBUG) updates.onForeground()
	}

	override fun onStop() {
		AppData.vpn.detach()
		super.onStop()
	}

	internal fun exitUiKeepingVpn() {
		if (ProfileViewModel.hasActiveDownloads) {
			Toast.makeText(this, R.string.exit_ui_profile_busy, Toast.LENGTH_SHORT).show()
			return
		}
		exitUiRequested = true
		// A second UI task could otherwise be resumed when this window finishes.
		getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
		finish()
	}

	override fun onDestroy() {
		super.onDestroy()
		// Normal backgrounding and configuration changes keep the UI process available.
		if (exitUiRequested && isFinishing) Process.killProcess(Process.myPid())
	}

	private fun requestNotificationPermission() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			when {
				ContextCompat.checkSelfPermission(
					this,
					Manifest.permission.POST_NOTIFICATIONS,
				) == PackageManager.PERMISSION_GRANTED -> {
					// 已有权限
					android.util.Log.i("clash", "Notification permission already granted")
				}
				else -> {
					// 请求权限
					notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
				}
			}
		}
	}
}
