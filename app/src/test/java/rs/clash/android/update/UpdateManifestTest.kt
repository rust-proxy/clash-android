package rs.clash.android.update

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdateManifestTest {
	private val arm =
		UpdateAsset(
			"test-arm64-v8a.apk",
			3,
			"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
		)
	private val release =
		UpdateManifest(
			"stable",
			"v1.0",
			"rs.clash.android",
			42,
			"1.0",
			assets = mapOf("x86" to arm.copy(file = "test-x86.apk"), "arm64-v8a" to arm, "all" to arm.copy(file = "test-all.apk")),
		)

	private fun select(
		manifest: UpdateManifest = release,
		channel: UpdateChannel = UpdateChannel.STABLE,
		code: Long = 41,
		abis: List<String> = listOf("arm64-v8a", "x86"),
	) = selectUpdate(Json.encodeToString(UpdateManifest.serializer(), manifest), channel, "rs.clash.android", code, abis)

	@Test
	fun usesDevicePreferenceThenUniversal() {
		assertEquals(arm, select()?.asset)
		assertEquals("x86", select(abis = listOf("x86", "arm64-v8a"))?.abi)
		assertEquals("all", select(abis = listOf("x86_64"))?.abi)
		assertThrows(UnsupportedAbi::class.java) { select(release.copy(assets = emptyMap())) }
	}

	@Test
	fun channelSwitchNeverDowngradesOrInstallsSameBuild() {
		val testing = release.copy(channel = "testing", tag = "latest")
		assertEquals(42L, select(testing, UpdateChannel.TESTING)?.manifest?.versionCode)
		assertThrows(InvalidUpdate::class.java) { select(testing) }
		assertThrows(InvalidUpdate::class.java) { select(release.copy(tag = "latest")) }
		assertNull(select(code = 42))
		assertNull(select(code = 43))
	}

	@Test
	fun rejectsWrongPackageAndUnsafeAssetNames() {
		assertThrows(InvalidUpdate::class.java) { select(release.copy(applicationId = "another.app")) }
		for (file in listOf("../other.apk", "https://example.com/other.apk", "download.zip")) {
			assertThrows(InvalidUpdate::class.java) { select(release.copy(assets = mapOf("all" to arm.copy(file = file)))) }
		}
	}

	@Test
	fun verifiesCachedApkSizeAndHash() {
		runTest { arm.verifyCachedBytes("abc".byteInputStream()) }
		for (body in listOf("ab", "abcd", "abd")) {
			assertThrows(InvalidUpdate::class.java) {
				runTest { arm.verifyCachedBytes(body.byteInputStream()) }
			}
		}
	}
}
