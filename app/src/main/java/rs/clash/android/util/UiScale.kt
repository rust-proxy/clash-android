package rs.clash.android.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import rs.clash.android.model.MAX_UI_SCALE
import rs.clash.android.model.MIN_UI_SCALE
import rs.clash.android.model.UI_SCALE_KEY
import rs.clash.android.model.recommendedUiScale

internal fun SharedPreferences.manualUiScale(): Int? = getInt(UI_SCALE_KEY, 0).takeIf { it in MIN_UI_SCALE..MAX_UI_SCALE }

@Suppress("DEPRECATION")
internal fun Context.recommendedUiScale(): Int {
	val display =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			this.display
		} else {
			getSystemService(WindowManager::class.java).defaultDisplay
		} ?: return 100
	val mode = display.mode
	val metrics = resources.displayMetrics
	// Use the default display's factory density so Android's display-size preference
	// still takes effect through the system density.
	val referenceDpi =
		if (display.displayId == Display.DEFAULT_DISPLAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			DisplayMetrics.DENSITY_DEVICE_STABLE
		} else {
			metrics.densityDpi
		}
	return recommendedUiScale(mode.physicalWidth, mode.physicalHeight, metrics.xdpi, metrics.ydpi, referenceDpi)
}
