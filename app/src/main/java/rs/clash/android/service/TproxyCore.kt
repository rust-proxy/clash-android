package rs.clash.android.service

import android.content.Context
import android.os.Looper
import android.os.Process
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import rs.clash.android.Application
import uniffi.clash_android_ffi.ProfileOverride
import uniffi.clash_android_ffi.runClash
import kotlin.system.exitProcess

/** Entry point in the installed APK. The core reads the selected profile as the app UID. */
internal object TproxyCore {
	const val PORT = 17893

	@JvmStatic
	private external fun dropPrivileges(uid: Int): Boolean

	fun command(args: Array<String>): List<String> =
		listOf(args[1], "-Djava.library.path=${args[0]}", "/system/bin", "--nice-name=clash-tproxy-core", TproxyCore::class.java.name) + args

	@JvmStatic
	fun main(args: Array<String>) {
		try {
			val (libs, runtime, pkg, uid) = args
			val lib = "$libs/libclash_android_ffi.so"
			System.load(lib)
			if (Process.myUid() == 0) {
				check(dropPrivileges(uid.toInt())) { "Cannot enter app UID with NET_RAW" }
				Os.execv(runtime, command(args).toTypedArray())
			}
			check(Process.myUid() == uid.toInt())
			System.setProperty("uniffi.component.clash_android_ffi.libraryOverride", lib)
			System.setProperty("jna.boot.library.path", libs)
			Looper.prepareMainLooper()
			val activityThread = Class.forName("android.app.ActivityThread")
			val thread = activityThread.getMethod("systemMain").invoke(null)
			val system = activityThread.getMethod("getSystemContext").invoke(thread) as Context
			val context = system.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
			check(Application().javaInit(context, null))
			val input = System.`in`.bufferedReader()
			val config = JSONObject(input.readLine() ?: return)
			runBlocking {
				val clash =
					runClash(
						config.getString("path"),
						config.getString("workDir"),
						ProfileOverride(
							mixedPort = 7890u,
							fakeIp = config.getBoolean("fakeIp"),
							ipv6 = config.getBoolean("ipv6"),
							tproxyPort = PORT.toUShort(),
						),
					)
				println("CORE ${Process.myPid()} ${clash.mixedPort()}")
				// The supervisor closes stdin after removing the kernel rules.
				// Exit on native failure even if the owner is still connected.
				val waiter =
					launch(Dispatchers.IO) {
						clash.awaitStopped()
						exitProcess(0)
					}
				input.readLine()
				waiter.cancel()
				clash.shutdown()
				exitProcess(0)
			}
		} catch (e: Throwable) {
			Log.e("ClashTproxy", "Core stopped during startup", e)
			exitProcess(1)
		}
	}
}
