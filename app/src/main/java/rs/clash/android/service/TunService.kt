package rs.clash.android.service

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import uniffi.clash_android_ffi.ProfileOverride
import uniffi.clash_android_ffi.runClash

@SuppressLint("VpnServicePolicy")
class TunService : VpnService() {
	private val session = ProxyServiceSession(this, ::runSession)

	override fun onBind(intent: Intent): IBinder? = session.bind(intent) ?: super.onBind(intent)

	override fun onUnbind(intent: Intent): Boolean {
		session.unbind(intent)
		return super.onUnbind(intent)
	}

	override fun onStartCommand(
		intent: Intent?,
		flags: Int,
		startId: Int,
	): Int = session.start(intent)

	override fun onRevoke() {
		Handler(Looper.getMainLooper()).post { session.stop() }
	}

	override fun onDestroy() {
		session.stop()
		super.onDestroy()
	}

	private suspend fun runSession(
		config: JSONObject,
		ready: (Int) -> Unit,
	) {
		val builder =
			Builder()
				.setSession("ClashRS VPNService")
				.addAddress("10.0.0.1", 30)
				.addRoute("0.0.0.0", 0)
				.addDnsServer("10.0.0.2")
				.allowBypass()
		val allowed = config.getString("mode") == "ALLOWED"
		if (!allowed) builder.addDisallowedApplication(packageName)
		val apps = config.getJSONArray("apps")
		for (index in 0 until apps.length()) {
			try {
				val app = apps.getString(index)
				if (allowed) builder.addAllowedApplication(app) else builder.addDisallowedApplication(app)
			} catch (_: PackageManager.NameNotFoundException) {
				// A selected app may have been uninstalled since the session was saved.
			}
		}

		val path = config.getString("path")
		val fakeIp = config.getBoolean("fakeIp")
		val ipv6 = config.getBoolean("ipv6")
		val fd = checkNotNull(builder.establish()) { "VPN permission revoked" }.use { it.detachFd() }
		// clash-rs takes ownership through AsyncDevice::from_fd. Reclaim the fd here only
		// when runClash fails before spawning the native task.
		val clash =
			try {
				runClash(path, cacheDir.path, ProfileOverride(fd, fakeIp = fakeIp, ipv6 = ipv6))
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				ParcelFileDescriptor.adoptFd(fd).close()
				throw e
			}
		try {
			ready(clash.mixedPort().toInt())
			clash.awaitStopped()
		} finally {
			clash.shutdown()
		}
	}
}
