package rs.clash.android.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
	val coreReady: Boolean = false,
) {
	val localProxyUrl: String? get() = mixedPort?.let { "http://127.0.0.1:$it" }
}

interface VpnController {
	val session: StateFlow<VpnSessionState>

	fun attach()

	fun detach()

	fun start(): VpnStartResult

	suspend fun restart(): VpnStartResult

	fun stop()
}

/** Port announcements during startup should not restart page requests. */
internal val VpnController.runningChanges: Flow<Boolean>
	get() = session.map { it.running }.distinctUntilChanged()

internal val VpnController.coreReadyChanges: Flow<Boolean>
	get() = session.map { it.coreReady }.distinctUntilChanged()

/** Routes operations to the native KernelSU session or the Android VPN service. */
internal class AndroidVpnController(
	private val context: Context,
) : VpnController {
	private val proxy = ProxySession(context)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private var restarting: Deferred<VpnStartResult>? = null
	private val native = NativeTproxy(context, scope)
	override val session =
		combine(proxy.state, native.state) { vpn, root -> if (root.running) root else vpn }
			.stateIn(scope, SharingStarted.Eagerly, native.state.value)

	override fun attach() {
		proxy.attach()
		native.attach()
	}

	override fun detach() {
		proxy.detach()
		native.detach()
	}

	override fun start(): VpnStartResult {
		if (session.value.running || restarting?.isActive == true) return VpnStartResult.Started
		val config = sessionConfig() ?: return VpnStartResult.MissingProfile
		if (JSONObject(config).optBoolean(NativeTproxy.MODE_KEY)) {
			native.enqueue("start", JSONObject(config))
			return VpnStartResult.Started
		}
		return withStartIntent(config) { ContextCompat.startForegroundService(context, it) }
	}

	override suspend fun restart(): VpnStartResult {
		restarting?.takeIf { it.isActive }?.let { return it.await() }
		if (!session.value.running) return VpnStartResult.Started
		val task =
			scope.async {
				// Keep observing across replacements, even if the Activity goes to the background.
				proxy.attach()
				try {
					var config: String?
					var result: VpnStartResult
					do {
						config = sessionConfig()
						result =
							if (native.state.value.running && config != null) {
								native.change("restart", JSONObject(config))
								VpnStartResult.Started
							} else {
								withStartIntent(config) { proxy.restart(it) }
							}
					} while (result == VpnStartResult.Started && session.value.running && config != sessionConfig())
					result
				} finally {
					proxy.detach()
				}
			}
		restarting = task
		return task.await()
	}

	private inline fun withStartIntent(
		config: String?,
		launch: (Intent) -> Unit,
	): VpnStartResult {
		if (config == null) return VpnStartResult.MissingProfile
		val permissionIntent = VpnService.prepare(context)
		if (permissionIntent != null) return VpnStartResult.PermissionRequired(permissionIntent)
		launch(Intent(context, TunService::class.java).putExtra(ProxyServiceSession.CONFIG, config))
		return VpnStartResult.Started
	}

	private fun sessionConfig(): String? {
		val path = context.getSharedPreferences("file_prefs", Context.MODE_PRIVATE).getString("profile_path", null)
		if (path.isNullOrEmpty()) return null
		val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
		val mode = prefs.getString("app_filter_mode", "ALL") ?: "ALL"
		val apps =
			when (mode) {
				"ALLOWED" -> prefs.getStringSet("allowed_apps", emptySet())
				"DISALLOWED" -> prefs.getStringSet("disallowed_apps", emptySet())
				else -> emptySet()
			}
		return JSONObject()
			.put("path", path)
			.put(NativeTproxy.MODE_KEY, prefs.getBoolean(NativeTproxy.MODE_KEY, false))
			.put("mode", mode)
			.put("apps", JSONArray(apps.orEmpty().sorted()))
			.put("fakeIp", prefs.getBoolean("fake_ip", false))
			.put("ipv6", prefs.getBoolean("ipv6", true))
			.toString()
	}

	override fun stop() {
		restarting?.cancel()
		if (native.state.value.running) {
			native.enqueue("stop")
		} else {
			context.startService(Intent(context, TunService::class.java).setAction(ProxyServiceSession.STOP))
		}
	}
}
