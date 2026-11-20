package rs.clash.android.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal enum class UpdateChannel(
	val id: String,
	val manifestPath: String,
) {
	STABLE("stable", "latest/download/update.json"),
	TESTING("testing", "download/latest/update.json"),
}

@Serializable
internal data class UpdateAsset(
	val file: String,
	val size: Long,
	val sha256: String,
)

@Serializable
internal data class UpdateManifest(
	val channel: String,
	val tag: String,
	val applicationId: String,
	val versionCode: Long,
	val versionName: String,
	val notes: String = "",
	val assets: Map<String, UpdateAsset>,
)

internal data class AppUpdate(
	val manifest: UpdateManifest,
	val abi: String,
	val asset: UpdateAsset,
)

internal class InvalidUpdate : IOException()

internal class UnsupportedAbi : IOException()

private val manifestJson = Json { ignoreUnknownKeys = true }

internal fun selectUpdate(
	json: String,
	channel: UpdateChannel,
	packageName: String,
	installedCode: Long,
	supportedAbis: List<String>,
): AppUpdate? {
	val release = manifestJson.decodeFromString<UpdateManifest>(json)
	val validTag =
		when (channel) {
			UpdateChannel.STABLE -> release.tag.matches(Regex("v[A-Za-z0-9_.+-]+"))
			UpdateChannel.TESTING -> release.tag == "latest"
		}
	if (!validTag ||
		release.channel != channel.id ||
		release.applicationId != packageName ||
		release.versionCode <= 0 ||
		release.versionName.isBlank()
	) {
		throw InvalidUpdate()
	}
	if (release.versionCode <= installedCode) return null
	val abi = (supportedAbis + "all").firstOrNull { it in release.assets } ?: throw UnsupportedAbi()
	val asset = release.assets.getValue(abi)
	if (!asset.file.matches(Regex("[A-Za-z0-9_.+-]+\\.apk")) ||
		asset.size <= 0 ||
		!asset.sha256.matches(Regex("[a-f0-9]{64}"))
	) {
		throw InvalidUpdate()
	}
	return AppUpdate(release, abi, asset)
}

internal suspend fun UpdateAsset.copyVerified(
	input: InputStream,
	output: OutputStream?,
	onBytes: suspend (Long) -> Unit,
) {
	val digest = MessageDigest.getInstance("SHA-256")
	val buffer = ByteArray(64 * 1024)
	var total = 0L
	while (true) {
		val count = input.read(buffer)
		if (count < 0) break
		total += count
		if (total > size) throw InvalidUpdate()
		digest.update(buffer, 0, count)
		output?.write(buffer, 0, count)
		onBytes(total)
	}
	if (total != size || digest.digest().joinToString("") { "%02x".format(it) } != sha256) throw InvalidUpdate()
}
