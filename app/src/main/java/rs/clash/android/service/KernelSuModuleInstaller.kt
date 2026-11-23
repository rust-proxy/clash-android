package rs.clash.android.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rs.clash.android.R
import rs.clash.android.ui.snackbar.SnackbarController
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KernelSuModuleProvider : FileProvider()

private fun ZipOutputStream.addModuleAsset(
	assets: AssetManager,
	path: String,
) {
	val asset = "kernelsu/$path"
	val children = assets.list(asset).orEmpty()
	if (children.isNotEmpty()) {
		for (child in children) addModuleAsset(assets, "$path/$child")
		return
	}
	putNextEntry(ZipEntry(path))
	assets.open(asset).use { it.copyTo(this) }
	closeEntry()
}

internal suspend fun requestKernelSuModuleInstall(context: Context) {
	try {
		val uri =
			withContext(Dispatchers.IO) {
				val (_, abi) = findTproxyCore(context.applicationInfo.installedApks)
				val directory = File(context.cacheDir, "kernelsu")
				val temp = File.createTempFile("module-", ".tmp", directory.apply { mkdirs() })
				val zip = File(directory, "clash-android-tproxy-$abi.zip")
				try {
					ZipOutputStream(temp.outputStream().buffered()).use { archive ->
						for (entry in context.assets.list("kernelsu").orEmpty()) {
							if (entry != "bin") archive.addModuleAsset(context.assets, entry)
						}
						archive.addModuleAsset(context.assets, "bin/$abi/clash-tproxy")
					}
					if (!temp.renameTo(zip)) throw IOException("Cannot prepare the module ZIP")
				} finally {
					temp.delete()
				}
				FileProvider.getUriForFile(context, "${context.packageName}.kernelsu", zip)
			}
		val intent =
			Intent(Intent.ACTION_VIEW)
				.setPackage("me.weishu.kernelsu")
				.setDataAndType(uri, "application/zip")
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		context.startActivity(intent)
	} catch (_: ActivityNotFoundException) {
		SnackbarController.showMessage(context.getString(R.string.ksu_manager_missing))
	} catch (_: SecurityException) {
		SnackbarController.showMessage(context.getString(R.string.ksu_module_open_failed))
	} catch (_: IOException) {
		SnackbarController.showMessage(context.getString(R.string.ksu_module_open_failed))
	}
}
