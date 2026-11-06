package rs.clash.android.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.flow.StateFlow
import rs.clash.android.Global

sealed interface VpnStartResult {
	data object Started : VpnStartResult

	data object MissingProfile : VpnStartResult

	data class PermissionRequired(
		val intent: Intent,
	) : VpnStartResult
}

interface VpnController {
	val running: StateFlow<Boolean>

	fun start(): VpnStartResult

	fun stop()
}

/** Android permission/service bridge; TunService exclusively owns core and TUN cleanup. */
class AndroidVpnController(
	private val context: Context = Global.application,
) : VpnController {
	override val running: StateFlow<Boolean> = Global.isServiceRunning

	override fun start(): VpnStartResult {
		if (running.value) return VpnStartResult.Started
		val path = context.getSharedPreferences("file_prefs", Context.MODE_PRIVATE).getString("profile_path", null)
		if (path.isNullOrEmpty()) return VpnStartResult.MissingProfile
		Global.profilePath = path
		val permissionIntent = VpnService.prepare(context)
		if (permissionIntent != null) return VpnStartResult.PermissionRequired(permissionIntent)
		context.startService(Intent(context, TunService::class.java))
		return VpnStartResult.Started
	}

	override fun stop() {
		tunService?.stopVpn()
	}
}
