package rs.clash.android.model

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

enum class ProfileType {
	LOCAL,
	REMOTE,
}

@Serializable
data class Profile(
	@Required val id: String = UUID.randomUUID().toString(),
	val name: String,
	val filePath: String,
	@Required val createdAt: Long = System.currentTimeMillis(),
	@Required val isActive: Boolean = false,
	@Required val fileSize: Long = 0,
	val type: ProfileType = ProfileType.LOCAL,
	val url: String? = null,
	val lastUpdated: Long? = null,
	val autoUpdate: Boolean = false,
	val userAgent: String? = null,
	val proxyUrl: String? = null,
)

internal object ProfileListJson {
	private val json =
		Json {
			ignoreUnknownKeys = true
			encodeDefaults = true
			explicitNulls = false
		}

	fun encode(profiles: List<Profile>): String = json.encodeToString(profiles)

	fun decode(value: String): List<Profile> =
		json.decodeFromString<List<Profile>>(value).map { profile ->
			// Earlier versions treated blank optional strings as absent when loading.
			profile.copy(
				url = profile.url?.takeIf(String::isNotBlank),
				userAgent = profile.userAgent?.takeIf(String::isNotBlank),
				proxyUrl = profile.proxyUrl?.takeIf(String::isNotBlank),
			)
		}
}
