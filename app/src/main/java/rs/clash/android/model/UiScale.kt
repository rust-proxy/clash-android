package rs.clash.android.model

import kotlin.math.hypot
import kotlin.math.roundToInt

internal const val UI_SCALE_KEY = "ui_scale_percent"
internal const val MIN_UI_SCALE = 75
internal const val MAX_UI_SCALE = 150
internal const val UI_SCALE_STEP = 5

internal data class UiScale(
	val recommended: Int,
	val manual: Int?,
) {
	val percent: Int get() = manual ?: recommended
}

internal fun recommendedUiScale(
	widthPx: Int,
	heightPx: Int,
	xdpi: Float,
	ydpi: Float,
	referenceDpi: Int,
): Int {
	if (widthPx <= 0 || heightPx <= 0 || referenceDpi <= 0 || xdpi !in 60f..1000f || ydpi !in 60f..1000f) return 100
	if (maxOf(xdpi, ydpi) / minOf(xdpi, ydpi) > 1.2f) return 100

	val diagonalInches = hypot(widthPx / xdpi, heightPx / ydpi)
	val physicalDpi = hypot(widthPx.toFloat(), heightPx.toFloat()) / diagonalInches
	val densityRatio = physicalDpi / referenceDpi
	if (diagonalInches !in 3f..40f || densityRatio !in 0.65f..1.65f) return 100

	// Allow slightly larger controls on tablet-sized panels, while keeping at
	// least 320 baseline dp across the shorter edge when possible.
	val tabletAdjustment = 1f + ((diagonalInches - 7f) * 0.025f).coerceIn(0f, 0.15f)
	val shortEdgeDp = minOf(widthPx, heightPx) * 160f / referenceDpi
	val scale = minOf(densityRatio * tabletAdjustment, shortEdgeDp / 320f)
	return (scale.coerceIn(0.85f, 1.25f) * 100 / UI_SCALE_STEP).roundToInt() * UI_SCALE_STEP
}
