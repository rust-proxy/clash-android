package rs.clash.android.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rs.clash.android.Global
import rs.clash.android.util.NotificationHelper
import rs.clash.android.util.PermissionHelper
import uniffi.clash_android_ffi.ProfileOverride
import uniffi.clash_android_ffi.runClash
import java.io.File

var tunService: TunService? = null

@SuppressLint("VpnServicePolicy")
class TunService : VpnService() {
	private var vpnInterface: ParcelFileDescriptor? = null

	/**
	 * Raw file descriptor of the established TUN device. `ParcelFileDescriptor.detachFd()`
	 * transfers ownership to us, so this descriptor has to be closed by hand: otherwise
	 * every VPN start leaks one descriptor (and the TUN device that owns it), which
	 * eventually fails the next `Builder.establish()` with "Too many open files".
	 */
	@Volatile
	private var tunFd: Int? = null

	/**
	 * Owner of the detached TUN descriptor. `detachFd()` hands the raw fd to us and
	 * closing the VpnService builder's ParcelFileDescriptor no longer releases it, so
	 * this second object exists purely to close the descriptor in [cleanup].
	 */
	private var tunOwner: ParcelFileDescriptor? = null

	/** The single in-flight [runVpn] call, so duplicate `onStartCommand`s cannot stack up. */
	private var vpnJob: Job? = null
	private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	/** Set while [cleanup] runs / after it finished, so [runVpn] can notice it lost the race. */
	@Volatile
	private var isDestroying = false

	override fun onStartCommand(
		intent: Intent?,
		flags: Int,
		startId: Int,
	): Int {
		Log.i("clash", "onStartCommand")

		startForegroundServiceIfNeeded()

		if (vpnJob?.isActive == true) {
			// A VPN session is already being established or running; a second one would
			// establish another TUN interface and replace `Global.clashInstance` without
			// shutting the first instance down.
			Log.i("clash", "VPN session already in progress, ignoring duplicate start")
		} else {
			vpnJob =
				serviceScope.launch {
					try {
						runVpn()
					} catch (e: Exception) {
						Log.e("clash", "Error in runVpn", e)
						stopVpn()
					}
				}
		}

		tunService = this
		Global.isServiceRunning.value = true
		return START_STICKY
	}

	override fun onCreate() {
		super.onCreate()
	}

	override fun onRevoke() {
		Log.i("clash", "onRevoke called")
		cleanup()
		super.onRevoke()
	}

	override fun onDestroy() {
		Log.i("clash", "onDestroy called")
		cleanup()
		super.onDestroy()
	}

	private suspend fun runVpn() {
		val prefs = Global.application.getSharedPreferences("settings", Context.MODE_PRIVATE)
		val builder = Builder()
		builder.setSession("ClashRS VPNService")
		builder.addAddress("10.0.0.1", 30)
		builder.addRoute("0.0.0.0", 0)

		builder.addDnsServer("10.0.0.2")
		
		// Apply app filter settings
		val appFilterMode = prefs.getString("app_filter_mode", "ALL") ?: "ALL"
		when (appFilterMode) {
			"ALLOWED" -> {
				val allowedApps = prefs.getStringSet("allowed_apps", emptySet()) ?: emptySet()
				allowedApps.forEach { packageName ->
					try {
						builder.addAllowedApplication(packageName)
						Log.d("clash", "Added allowed app: $packageName")
					} catch (e: Exception) {
						Log.e("clash", "Failed to add allowed app: $packageName", e)
					}
				}
			}
			"DISALLOWED" -> {
				val disallowedApps = prefs.getStringSet("disallowed_apps", emptySet()) ?: emptySet()
				// Always disallow self
				builder.addDisallowedApplication(packageName)
				disallowedApps.forEach { packageName ->
					try {
						builder.addDisallowedApplication(packageName)
						Log.d("clash", "Added disallowed app: $packageName")
					} catch (e: Exception) {
						Log.e("clash", "Failed to add disallowed app: $packageName", e)
					}
				}
			}
			else -> {
				// ALL mode - disallow only self
				builder.addDisallowedApplication(packageName)
			}
		}
		
		builder.allowBypass()
		vpnInterface = builder.establish()

		tunFd = vpnInterface?.detachFd()
		// `detachFd()` transferred the descriptor to us; give it back to a
		// ParcelFileDescriptor so `cleanup()` has something that can actually close it,
		// while the raw fd stays open for the kernel.
		tunOwner = tunFd?.let { ParcelFileDescriptor.adoptFd(it) }
		// Closing the detached wrapper is a no-op for the descriptor itself (it only
		// releases the Java side comm buffer), it just keeps the object lifetime tidy.
		try {
			vpnInterface?.close()
		} catch (e: Exception) {
			Log.w("clash", "Failed to close detached VPN interface wrapper", e)
		}
		vpnInterface = null

		val fd =
			tunFd ?: run {
				Log.e("clash", "VPN interface fd is null, aborting")
				stopVpn()
				return
			}
		val assets = Global.application.assets
		listOf("Country.mmdb", "geosite.dat").forEach { name ->
			assets
				.open("clash-res/$name")
				.use { it ->
					val file = File("${Global.application.cacheDir}/$name")
					file.deleteOnExit()
					file.createNewFile()
					it.copyTo(file.outputStream())
				}
		}

		val instance =
			runClash(
				Global.profilePath,
				Global.application.cacheDir.toString(),
				ProfileOverride(
					fd,
					fakeIp = prefs.getBoolean("fake_ip", false),
					ipv6 = prefs.getBoolean("ipv6", true),
				),
			)

		if (isDestroying || vpnJob?.isActive != true) {
			// The service was stopped (or revoked) while the kernel was starting up.
			// Publishing the instance now would leave a running clash-rs core with no
			// owner and no way to shut it down, so shut it down here instead.
			Log.w("clash", "VPN service stopped while starting clash-rs, shutting the new instance down")
			runCatching { instance.shutdown() }
				.onFailure { Log.e("clash", "Failed to shut down orphaned clash-rs instance", it) }
			return
		}

		Global.clashInstance = instance
	}

	/**
	 * Puts the service into the foreground state - unconditionally - and dismisses the
	 * ongoing notification right away when the user disabled "keep alive".
	 *
	 * `TunService` declares `foregroundServiceType="specialUse"` with the matching
	 * `FOREGROUND_SERVICE_SPECIAL_USE` permission, so on API 34+ the platform expects it to
	 * enter the foreground legally: `startForeground()` without a type throws
	 * `MissingForegroundServiceTypeException`, and a service that never enters the foreground
	 * is refused/killed once it is started from the background
	 * (`ForegroundServiceStartNotAllowedException`). Entering the foreground is therefore not
	 * optional any more - only the *notification* is.
	 *
	 * The "keep alive" preference decides exactly that: with the preference off the notification
	 * is cancelled immediately after the foreground start. `STOP_FOREGROUND_DETACH` is not the
	 * alternative here - the platform documents that a detached notification "remains shown even
	 * after this service is stopped fully and destroyed", which is the opposite of what the
	 * preference asks for.
	 *
	 * Trade-off, stated honestly because it is *not* verifiable without a device:
	 * `Service.stopForeground(int)` is documented as removing the service from the foreground
	 * state ("allowing it to be killed if more memory is needed"), so with "keep alive" off this
	 * drops the usual foreground-service priority and the VPN may be killed under memory
	 * pressure. That is the same exposure the previous implementation had (it never called
	 * `startForeground()` at all in that configuration), and it is the only way to honour a
	 * preference that explicitly asks for no ongoing notification. If that exposure ever shows up
	 * as real, the fix is to keep the notification and drop the preference, not the other way
	 * around.
	 *
	 * A refused foreground start is only logged, because this path can only be verified on a
	 * real device.
	 */
	private fun startForegroundServiceIfNeeded() {
		val prefs = Global.application.getSharedPreferences("settings", Context.MODE_PRIVATE)
		val foregroundServiceEnabled = prefs.getBoolean("foreground_service_enabled", false)

		if (foregroundServiceEnabled && !PermissionHelper.hasNotificationPermission(this)) {
			// 检查通知权限
			Log.w("clash", "Keep-alive is enabled but notification permission is not granted")
			// 即使没有权限，仍然尝试启动前台服务（Android 13以下不需要权限）
		}

		try {
			val notification = NotificationHelper.createNotification(this)

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startForeground(
					NotificationHelper.NOTIFICATION_ID,
					notification,
					ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
				)
			} else {
				startForeground(NotificationHelper.NOTIFICATION_ID, notification)
			}

			if (foregroundServiceEnabled) {
				Log.i("clash", "Started foreground service with SPECIAL_USE type")
			} else {
				dismissForegroundNotification()
			}
		} catch (e: Exception) {
			// A refused foreground start must not take the whole VPN down with it.
			Log.e("clash", "Failed to enter the foreground state", e)
		}
	}

	/**
	 * Cancels the ongoing notification while the VPN keeps running, so users who turned
	 * "keep alive" off do not get a permanent notification.
	 */
	@Suppress("DEPRECATION")
	private fun dismissForegroundNotification() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			stopForeground(STOP_FOREGROUND_REMOVE)
		} else {
			stopForeground(true)
		}
		Log.i("clash", "Foreground notification dismissed (keep-alive disabled)")
	}

	private fun cleanup() {
		synchronized(this) {
			if (isDestroying) {
				return
			}
			isDestroying = true
		}
		Log.i("clash", "Cleaning up VPN service")
		// shutdown clash-rs via ClashInstance
		Global.clashInstance?.shutdown()
		Global.clashInstance = null

		try {
			vpnInterface?.close()
		} catch (e: Exception) {
			Log.e("clash", "Error closing VPN interface", e)
		}

		vpnInterface = null

		// The descriptor came from `detachFd()`, so closing the original builder
		// ParcelFileDescriptor did not release it. Close the adopted owner explicitly,
		// otherwise the VPN interface outlives the service and the descriptor leaks.
		try {
			tunOwner?.close()
		} catch (e: Exception) {
			Log.e("clash", "Error closing TUN descriptor", e)
		}
		tunOwner = null
		tunFd = null

		tunService = null
		Global.isServiceRunning.value = false
	}

	fun stopVpn() {
		cleanup()
		stopSelf()
	}
}
