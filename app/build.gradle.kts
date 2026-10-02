import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.ksp)
	alias(libs.plugins.ktlint)
}

val baseVersionName = "0.4.1"
val Project.verName: String get() = "${baseVersionName}$versionNameSuffix.${exec("git rev-parse --short HEAD")}"
val Project.verCode: Int get() = exec("git rev-list --count HEAD").toInt()
val Project.isDevVersion: Boolean get() = exec("git tag -l v$baseVersionName").isEmpty()
val Project.versionNameSuffix: String get() = if (isDevVersion) ".dev" else ""

fun Project.exec(command: String): String =
	providers
		.exec {
			commandLine(command.split(" "))
		}.standardOutput.asText
		.get()
		.trim()

fun env(key: String): String? = System.getenv(key).let { if (it.isNullOrEmpty()) null else it }

/**
 * Resolves the version of the Android component of `rustls-platform-verifier`
 * from the Cargo lockfile.
 *
 * `rustls-platform-verifier` is a Rust crate, but on Android it calls into a
 * Kotlin component (`org.rustls.platformverifier`) which is shipped as an AAR.
 * That AAR must have exactly the same version as the
 * `rustls-platform-verifier-android` crate Cargo resolved, otherwise the JNI
 * handshake between the two halves fails at runtime.
 */
abstract class RustlsPlatformVerifierVersion : ValueSource<String, RustlsPlatformVerifierVersion.Params> {
	interface Params : ValueSourceParameters {
		val lockFile: RegularFileProperty
	}

	companion object {
		const val CRATE_NAME = "rustls-platform-verifier-android"
	}

	override fun obtain(): String {
		val version =
			parameters.lockFile.get().asFile.readLines().let { lines ->
				val nameIdx = lines.indexOfFirst { it.trim() == "name = \"$CRATE_NAME\"" }
				if (nameIdx < 0) {
					null
				} else {
					lines
						.drop(nameIdx + 1)
						.firstOrNull { it.trimStart().startsWith("version = ") }
						?.substringAfter('"', "")
						?.substringBefore('"', "")
						?.takeIf { it.isNotEmpty() }
				}
			}
		return version ?: error("$CRATE_NAME not found in Cargo.lock")
	}
}

val rustlsPlatformVerifierVersion =
	providers.of(RustlsPlatformVerifierVersion::class.java) {
		parameters.lockFile.set(layout.projectDirectory.file("../uniffi/Cargo.lock"))
	}

// The Kotlin component is declared without a version in the version catalog so
// that it always tracks the Rust crate resolved by Cargo.
configurations.configureEach {
	resolutionStrategy.eachDependency {
		if (requested.group == "org.rustls" && requested.name == "rustls-platform-verifier") {
			useVersion(rustlsPlatformVerifierVersion.get())
			because("the Kotlin component must match the version of the ${RustlsPlatformVerifierVersion.CRATE_NAME} Rust crate")
		}
	}
}

android {
	buildToolsVersion = rootProject.extra["buildToolsVersion"] as String
	val keystore = env("KEYSTORE_FILE")

	namespace = "rs.clash.android"
	compileSdk = 37
	defaultConfig {
		applicationId = "rs.clash.android"
		minSdk = 23
		targetSdk = 37
		versionCode = verCode
		versionName = verName

		resValue("string", "app_name", if (keystore == null) "clash android dev" else "clash android")
		resValue("string", "app_ver", verName)

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		ndk {
			abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
		}
		proguardFiles(
			getDefaultProguardFile("proguard-android-optimize.txt"),
			"proguard-rules.pro",
		)
	}

	signingConfigs {
		if (keystore == null) {
			return@signingConfigs
		}
		create("release") {
			storeFile = file(keystore)
			storePassword = env("KEYSTORE_PASSWORD")
			keyAlias = env("KEY_ALIAS")
			keyPassword = env("KEY_PASSWORD")
		}
	}

	buildTypes {
		release {
			isMinifyEnabled = true
   			isShrinkResources = true
			if (keystore != null) {
				signingConfig = signingConfigs.getByName("release")
			}
		}
		debug {
			isMinifyEnabled = false
			if (keystore == null) {
				applicationIdSuffix = ".dev"
			}
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_25
		targetCompatibility = JavaVersion.VERSION_25
	}
	buildFeatures {
		compose = true
		resValues = true
	}
	splits {
		abi {
			isEnable = env("ANDROID_SPLIT_ABI_ENABLE") == "true"
			reset()
			include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
			isUniversalApk = env("ANDROID_SPLIT_ABI_UNIVERSAL_APK") == "true"
		}
	}
}
kotlin {
	jvmToolchain(25)
}

dependencies {
	implementation(project(":core"))
	// Kotlin half of `rustls-platform-verifier`; the version is pinned to the
	// `rustls-platform-verifier-android` crate by the resolution strategy above.
	implementation(libs.rustls.platform.verifier)
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.androidx.runtime.livedata)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)

	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.ui)
	implementation(libs.androidx.ui.graphics)
	implementation(libs.androidx.ui.tooling.preview)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.material3)
	implementation(libs.androidx.material.icons.extended)
	implementation(libs.compose.destinations.core)

	ksp(libs.compose.destinations.ksp)

	testImplementation(libs.junit)
	androidTestImplementation(libs.androidx.junit)
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(platform(libs.androidx.compose.bom))
	androidTestImplementation(libs.androidx.ui.test.junit4)
	debugImplementation(libs.androidx.ui.tooling)
	debugImplementation(libs.androidx.ui.test.manifest)

	ktlintRuleset(libs.ktlint.compose.rules)
}
