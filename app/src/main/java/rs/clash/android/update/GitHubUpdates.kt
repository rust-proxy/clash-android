package rs.clash.android.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import rs.clash.android.BuildConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class GitHubUpdates(
	private val context: Context,
) {
	private val releases = "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases"

	suspend fun check(channel: UpdateChannel): AppUpdate? =
		withContext(Dispatchers.IO) {
			readAsset("$releases/${channel.manifestPath}") { input ->
				// Release notes are capped by CI. Bound the metadata read even on a bad response.
				val buffer = ByteArray(64 * 1024)
				var length = 0
				while (length < buffer.size) {
					val count = input.read(buffer, length, buffer.size - length)
					if (count < 0) break
					length += count
				}
				if (length == buffer.size) throw InvalidUpdate()
				selectUpdate(
					String(buffer, 0, length, Charsets.UTF_8),
					channel,
					context.packageName,
					BuildConfig.VERSION_CODE.toLong(),
					Build.SUPPORTED_ABIS.toList(),
				)
			}
		}

	private val directory = File(context.cacheDir, "app-updates")

	private fun destination(update: AppUpdate) = File(directory, "${update.asset.sha256}.apk")

	suspend fun verifiedCache(update: AppUpdate): File? =
		withContext(Dispatchers.IO) {
			val file = destination(update)
			if (!file.isFile) return@withContext null
			try {
				file.inputStream().use { input ->
					update.asset.copyVerified(input, null) { currentCoroutineContext().ensureActive() }
				}
				verifyPackage(file, update)
				file
			} catch (_: InvalidUpdate) {
				file.delete()
				null
			}
		}

	suspend fun download(
		update: AppUpdate,
		onProgress: suspend (Int) -> Unit,
	): File =
		withContext(Dispatchers.IO) {
			verifiedCache(update)?.let { return@withContext it }
			if (!directory.isDirectory && !directory.mkdirs()) throw IOException("APK cache unavailable")
			val final = destination(update)
			val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
			directory.listFiles()?.filter { it != final && it.lastModified() < cutoff }?.forEach { it.delete() }
			var lastProgress = -1
			val report: suspend (Long) -> Unit = { bytes ->
				currentCoroutineContext().ensureActive()
				val percent = (bytes.toDouble() / update.asset.size * 100).toInt()
				if (percent != lastProgress) {
					lastProgress = percent
					onProgress(percent)
				}
			}
			val partial = File.createTempFile("download-", ".apk", directory)
			try {
				readAsset("$releases/download/${update.manifest.tag}/${update.asset.file}") { input ->
					partial.outputStream().use { update.asset.copyVerified(input, it, report) }
				}
				currentCoroutineContext().ensureActive()
				verifyPackage(partial, update)
				if (!partial.renameTo(final)) throw IOException("Cannot save verified APK")
				final
			} finally {
				partial.delete()
			}
		}

	@Suppress("DEPRECATION")
	private fun verifyPackage(
		file: File,
		update: AppUpdate,
	) {
		val pm = context.packageManager
		val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
		val flags = if (modern) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
		val archive = pm.getPackageArchiveInfo(file.path, flags) ?: throw InvalidUpdate()
		if (archive.packageName != context.packageName || PackageInfoCompat.getLongVersionCode(archive) != update.manifest.versionCode) {
			throw InvalidUpdate()
		}
		val installed = pm.getPackageInfo(context.packageName, flags)
		val matches =
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				val current =
					installed.signingInfo
						?.apkContentsSigners
						?.toSet()
						.orEmpty()
				val candidate = archive.signingInfo ?: throw InvalidUpdate()
				current.isNotEmpty() &&
					(
						current == candidate.apkContentsSigners.toSet() ||
							(current.size == 1 && current.single() in candidate.signingCertificateHistory.orEmpty())
					)
			} else {
				val current = installed.signatures?.toSet().orEmpty()
				current.isNotEmpty() && current == archive.signatures?.toSet()
			}
		if (!matches) throw InvalidUpdate()
	}

	private suspend fun <T> readAsset(
		address: String,
		read: suspend (InputStream) -> T,
	): T {
		var url = URL(address)
		repeat(6) {
			currentCoroutineContext().ensureActive()
			if (url.protocol != "https") throw InvalidUpdate()
			val connection = url.openConnection() as HttpsURLConnection
			try {
				connection.instanceFollowRedirects = false
				connection.connectTimeout = 15_000
				connection.readTimeout = 30_000
				connection.useCaches = false
				when (val status = connection.responseCode) {
					200 -> return connection.inputStream.use { read(it) }
					301, 302, 303, 307, 308 -> url = URL(url, connection.getHeaderField("Location") ?: throw InvalidUpdate())
					else -> throw IOException("HTTP $status")
				}
			} finally {
				connection.disconnect()
			}
		}
		throw IOException("Too many redirects")
	}
}
