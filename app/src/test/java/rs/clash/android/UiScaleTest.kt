package rs.clash.android

import org.junit.Assert.assertEquals
import org.junit.Test
import rs.clash.android.model.recommendedUiScale

class UiScaleTest {
	@Test
	fun recommendationUsesPhysicalSizeAndResolution() {
		assertEquals(100, recommendedUiScale(1080, 2400, 400f, 400f, 400))
		assertEquals(125, recommendedUiScale(1080, 2400, 500f, 500f, 400))
		assertEquals(105, recommendedUiScale(1600, 2560, 320f, 320f, 320))
		assertEquals(90, recommendedUiScale(720, 1440, 400f, 400f, 400))
	}

	@Test
	fun rotationAndEquivalentHigherResolutionPreserveScale() {
		val percent = recommendedUiScale(1080, 2400, 430f, 435f, 400)
		assertEquals(percent, recommendedUiScale(2400, 1080, 435f, 430f, 400))
		assertEquals(percent, recommendedUiScale(2160, 4800, 860f, 870f, 800))
	}

	@Test
	fun missingOrImplausibleMeasurementsUseSystemScale() {
		assertEquals(100, recommendedUiScale(0, 2400, 440f, 440f, 420))
		assertEquals(100, recommendedUiScale(1080, 2400, Float.NaN, 440f, 420))
		assertEquals(100, recommendedUiScale(1080, 2400, 440f, 160f, 420))
		assertEquals(100, recommendedUiScale(1080, 2400, 160f, 160f, 420))
		assertEquals(100, recommendedUiScale(1080, 2400, 440f, 440f, 0))
	}
}
