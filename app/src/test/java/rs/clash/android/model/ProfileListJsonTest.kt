package rs.clash.android.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileListJsonTest {
	// The first list format predates subscriptions and contains only these six fields.
	private val local = """{"id":"l","name":"本地","filePath":"/profiles/l","createdAt":42,"isActive":false,"fileSize":0}"""
	private val remote =
		"""
		{
		  "id":"r","name":"远程","filePath":"/profiles/r","createdAt":123,"isActive":true,"fileSize":345,
		  "type":"REMOTE","url":"https://example.org/sub?a=1&b=2","lastUpdated":456,"autoUpdate":true,
		  "userAgent":"client/1.0","proxyUrl":"http://127.0.0.1:7890"
		}
		""".trimIndent()

	@Test
	fun loadsMixedGenerationsInOrderAndKeepsActiveProfile() {
		val expectedLocal = Profile(id = "l", name = "本地", filePath = "/profiles/l", createdAt = 42)
		val expectedRemote =
			Profile(
				id = "r",
				name = "远程",
				filePath = "/profiles/r",
				createdAt = 123,
				isActive = true,
				fileSize = 345,
				type = ProfileType.REMOTE,
				url = "https://example.org/sub?a=1&b=2",
				lastUpdated = 456,
				autoUpdate = true,
				userAgent = "client/1.0",
				proxyUrl = "http://127.0.0.1:7890",
			)

		val profiles = ProfileListJson.decode("[$local,$remote]")
		assertEquals(listOf(expectedLocal, expectedRemote), profiles)
		assertEquals(profiles, ProfileListJson.decode(ProfileListJson.encode(profiles)))
	}

	@Test
	fun writesTheSameArrayAndKeysAsThePreviousWriter() {
		val oldLocal = local.dropLast(1) + ""","type":"LOCAL","autoUpdate":false}"""
		val previous = "[$remote,$oldLocal]"
		val encoded = ProfileListJson.encode(ProfileListJson.decode(previous))
		assertEquals(Json.parseToJsonElement(previous), Json.parseToJsonElement(encoded))
	}

	@Test
	fun blankOptionalStringsBecomeNullAndExtraKeysAreIgnored() {
		val fields = ""","url":"  ","userAgent":"","proxyUrl":"\t","unknown":{"new":true}}"""
		val profile = ProfileListJson.decode("[${local.dropLast(1)}$fields]").single()
		assertNull(profile.url)
		assertNull(profile.userAgent)
		assertNull(profile.proxyUrl)
	}

	@Test
	fun rejectsMissingRequiredFieldsInsteadOfGeneratingReplacementValues() {
		val json = Json.parseToJsonElement(local).jsonObject
		for (field in listOf("id", "name", "filePath", "createdAt", "isActive", "fileSize")) {
			val incomplete = JsonObject(json - field)
			assertThrows("Missing $field", SerializationException::class.java) {
				ProfileListJson.decode("[$remote,$incomplete]")
			}
		}
	}

	@Test
	fun emptyListRoundTrips() {
		assertEquals(emptyList<Profile>(), ProfileListJson.decode("[]"))
		assertEquals("[]", ProfileListJson.encode(emptyList()))
	}
}
