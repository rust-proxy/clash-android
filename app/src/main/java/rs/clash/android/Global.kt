package rs.clash.android

import android.content.Context
import android.util.Log
import uniffi.clash_android_ffi.EyreException
import uniffi.clash_android_ffi.formatEyreError
import android.app.Application as AndroidApplication

class Application : AndroidApplication() {
	private external fun javaInit(context: Context)

	override fun onCreate() {
		super.onCreate()
		// Setup uncaught exception handler
		setupUncaughtExceptionHandler()
		Global.application = this
		System.loadLibrary("clash_android_ffi")
		javaInit(this)
	}
}

object Global {
	lateinit var application: Application
}

private fun setupUncaughtExceptionHandler() {
	val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

	Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
		try {
			// Check if the exception is EyreException
			if (throwable is EyreException) {
				val errorMessage = formatEyreError(throwable)
				Log.e("Clash", "Uncaught EyreException on thread ${thread.name}:")
				Log.e("Clash", errorMessage)

				// Also print to stderr
				System.err.println("Uncaught EyreException on thread ${thread.name}:")
				System.err.println(errorMessage)
			} else {
				// For other exceptions, log normally
				Log.e("Clash", "Uncaught exception on thread ${thread.name}:", throwable)
			}
		} catch (e: Exception) {
			// If something goes wrong in our handler, log it
			Log.e("Clash", "Error in exception handler:", e)
		} finally {
			// Call the default handler to let the system handle the crash
			defaultHandler?.uncaughtException(thread, throwable)
		}
	}
}
