package rs.clash.android.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
						for (entry in listOf("module.prop", "customize.sh", "skip_mount", "bin/$abi/clash-tproxy")) {
							archive.putNextEntry(ZipEntry(entry))
							context.assets.open("kernelsu/$entry").use { it.copyTo(archive) }
							archive.closeEntry()
						}
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
