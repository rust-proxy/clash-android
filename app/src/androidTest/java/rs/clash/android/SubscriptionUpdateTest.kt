package rs.clash.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import uniffi.clash_android_ffi.EyreException
import uniffi.clash_android_ffi.SubscriptionUpdate
import uniffi.clash_android_ffi.formatEyreError
import uniffi.clash_android_ffi.updateSubscription
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class SubscriptionUpdateTest {
	@get:Rule
	val folder = TemporaryFolder(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

	private val original = "mixed-port: 7890\n".toByteArray()
	private val updated = "mixed-port: 7891\n".toByteArray()
	private lateinit var profile: File

	@Before
	fun createProfile() {
		profile = folder.newFile("profile.yaml")
		profile.writeBytes(original)
	}

	@Test
	fun completeValidDownloadReplacesProfile() =
		runBlocking {
			val result = download(updated)
			assertEquals(SubscriptionUpdate.Updated(updated.size.toULong()), result)
			assertProfile(updated)
		}

	@Test
	fun truncatedDownloadPreservesProfile() =
		runBlocking {
			val failure = runCatching { download(updated, contentLength = updated.size + 100) }.exceptionOrNull()
			assertNativeError(failure, "Failed to read download")
			assertProfile(original)
		}

	@Test
	fun httpErrorPreservesProfile() =
		runBlocking {
			val failure = runCatching { download(updated, status = "503 Service Unavailable") }.exceptionOrNull()
			assertNativeError(failure, "HTTP 503")
			assertProfile(original)
		}

	@Test
	fun invalidConfigPreservesProfile() =
		runBlocking {
			val result = download("rules: [".toByteArray())
			assertTrue(result is SubscriptionUpdate.InvalidConfig)
			assertProfile(original)
		}

	@Test
	fun failedReplacementPreservesProfile() =
		runBlocking {
			// A trailing slash on a regular file makes rename(2) fail after validation.
			val failure = runCatching { download(updated, destination = profile.path + "/") }.exceptionOrNull()
			assertNativeError(failure, "Failed to replace subscription")
			assertProfile(original)
		}

	private fun assertNativeError(
		failure: Throwable?,
		expected: String,
	) {
		assertTrue("Expected a native error, got $failure", failure is EyreException)
		val message = formatEyreError(failure as EyreException)
		assertTrue(message, expected in message)
	}

	private fun assertProfile(expected: ByteArray) {
		assertArrayEquals(expected, profile.readBytes())
		assertEquals(listOf(profile.name), folder.root.list()?.toList())
	}

	private suspend fun download(
		body: ByteArray,
		contentLength: Int = body.size,
		status: String = "200 OK",
		destination: String = profile.path,
	): SubscriptionUpdate =
		coroutineScope {
			ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
				server.soTimeout = 10_000
				val responder =
					async(Dispatchers.IO) {
						server.accept().use { socket ->
							socket.soTimeout = 10_000
							val reader = socket.getInputStream().bufferedReader()
							while (!reader.readLine().isNullOrEmpty()) {
								// Consume the request headers before replying.
							}
							val output = socket.getOutputStream()
							output.write("HTTP/1.1 $status\r\nContent-Length: $contentLength\r\nConnection: close\r\n\r\n".toByteArray())
							output.write(body)
						}
					}
				val result = updateSubscription("http://127.0.0.1:${server.localPort}/config", destination, null, null, null)
				responder.await()
				result
			}
		}
}
