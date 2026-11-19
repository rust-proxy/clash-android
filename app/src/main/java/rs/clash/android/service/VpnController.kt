package rs.clash.android.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

sealed interface VpnStartResult {
	data object Started : VpnStartResult

	data object MissingProfile : VpnStartResult

	data class PermissionRequired(
		val intent: Intent,
	) : VpnStartResult
}

data class VpnSessionState(
	val running: Boolean = false,
	val mixedPort: Int? = null,
) {
	val localProxyUrl: String? get() = mixedPort?.let { "http://127.0.0.1:$it" }
}

interface VpnController {
	val session: StateFlow<VpnSessionState>

	fun attach()

	fun detach()

	fun start(): VpnStartResult

	fun stop()
}

/** Port announcements during startup should not restart page requests. */
internal val VpnController.runningChanges: Flow<Boolean>
	get() = session.map { it.running }.distinctUntilChanged()

/** Android permission/service bridge; TunService exclusively owns core and TUN cleanup. */
internal class AndroidVpnController(
	private val context: Context,
) : VpnController {
	private val proxy = ProxySession(context)
	override val session = proxy.state

	override fun attach() = proxy.attach()

	override fun detach() = proxy.detach()

	override fun start(): VpnStartResult {
		if (session.value.running) return VpnStartResult.Started
		val path = context.getSharedPreferences("file_prefs", Context.MODE_PRIVATE).getString("profile_path", null)
		if (path.isNullOrEmpty()) return VpnStartResult.MissingProfile
		val permissionIntent = VpnService.prepare(context)
		if (permissionIntent != null) return VpnStartResult.PermissionRequired(permissionIntent)
		val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
		val mode = prefs.getString("app_filter_mode", "ALL") ?: "ALL"
		val apps =
			when (mode) {
				"ALLOWED" -> prefs.getStringSet("allowed_apps", emptySet())
				"DISALLOWED" -> prefs.getStringSet("disallowed_apps", emptySet())
				else -> emptySet()
			}
		val config =
			JSONObject()
				.put("path", path)
				.put("mode", mode)
				.put("apps", JSONArray(apps.orEmpty()))
				.put("fakeIp", prefs.getBoolean("fake_ip", false))
				.put("ipv6", prefs.getBoolean("ipv6", true))
		val intent = Intent(context, TunService::class.java).putExtra(TunService.CONFIG, config.toString())
		ContextCompat.startForegroundService(context, intent)
		return VpnStartResult.Started
	}

	override fun stop() {
		context.startService(Intent(context, TunService::class.java).setAction(TunService.STOP))
	}
}
