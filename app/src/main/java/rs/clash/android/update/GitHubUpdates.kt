package rs.clash.android.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rs.clash.android.BuildConfig
import rs.clash.android.Global
import rs.clash.android.data.AppData
import uniffi.clash_android_ffi.DownloadProgress
import uniffi.clash_android_ffi.DownloadProgressCallback
import uniffi.clash_android_ffi.downloadApk
import uniffi.clash_android_ffi.fetchUpdateManifest
import java.io.File
import java.io.IOException

internal class NativeTlsUnavailable : IOException()

internal class GitHubUpdates(
	private val context: Context,
) {
	private val releases = "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases"

	suspend fun check(channel: UpdateChannel): AppUpdate? =
		withContext(Dispatchers.IO) {
			if (!Global.application.nativeTlsReady) throw NativeTlsUnavailable()
			val manifest =
				fetchUpdateManifest("$releases/${channel.manifestPath}", AppData.vpn.session.value.localProxyUrl)
					?: throw InvalidUpdate()
			selectUpdate(manifest, channel, context.packageName, BuildConfig.VERSION_CODE.toLong(), Build.SUPPORTED_ABIS.toList())
		}

	private val directory = File(context.cacheDir, "app-updates")

	private fun destination(update: AppUpdate) = File(directory, "${update.asset.sha256}.apk")

	suspend fun verifiedCache(update: AppUpdate): File? =
		withContext(Dispatchers.IO) {
			val file = destination(update)
			if (!file.isFile) return@withContext null
			try {
				file.inputStream().use { input ->
					update.asset.verifyCachedBytes(input)
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
		reportProgress: (Int) -> Unit,
	): File =
		withContext(Dispatchers.IO) {
			verifiedCache(update)?.let { return@withContext it }
			if (!Global.application.nativeTlsReady) throw NativeTlsUnavailable()
			if (!directory.isDirectory && !directory.mkdirs()) throw IOException("APK cache unavailable")
			val final = destination(update)
			val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
			directory.listFiles()?.filter { it != final && it.lastModified() < cutoff }?.forEach { it.delete() }
			val partial = File.createTempFile("download-", ".apk", directory)
			try {
				val callback =
					object : DownloadProgressCallback {
						override fun onProgress(progress: DownloadProgress) {
							val percent = (progress.downloaded.toDouble() / update.asset.size * 100).toInt()
							launch(Dispatchers.Main.immediate) { reportProgress(percent) }
						}
					}
				val verified =
					downloadApk(
						"$releases/download/${update.manifest.tag}/${update.asset.file}",
						partial.absolutePath,
						update.asset.size.toULong(),
						update.asset.sha256,
						AppData.vpn.session.value.localProxyUrl,
						callback,
					)
				if (!verified) throw InvalidUpdate()
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
}
