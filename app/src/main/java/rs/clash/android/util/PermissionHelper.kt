package rs.clash.android.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionHelper {
	/**
	 * 检查是否有通知权限
	 */
	fun hasNotificationPermission(context: Context): Boolean =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			ContextCompat.checkSelfPermission(
				context,
				Manifest.permission.POST_NOTIFICATIONS,
			) == PackageManager.PERMISSION_GRANTED
		} else {
			// Android 13 以下不需要动态请求通知权限
			true
		}
}
