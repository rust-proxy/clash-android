import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.google.services)
	alias(libs.plugins.firebase.crashlytics)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.ksp)
	alias(libs.plugins.ktlint)
}

googleServices {
	missingGoogleServicesStrategy = MissingGoogleServicesStrategy.IGNORE
}

val kernelSuSource = rootProject.layout.projectDirectory.dir("kernelsu")
val kernelSuTargets =
	mapOf("arm64-v8a" to "aarch64-linux-android", "armeabi-v7a" to "armv7-linux-androideabi", "x86_64" to "x86_64-linux-android")
val kernelSuCargoDir = layout.buildDirectory.dir("kernelsu-cargo")
val phoneArm64 = providers.gradleProperty("phoneArm64").map(String::toBoolean).getOrElse(false)
val kernelSuLaunchers =
	kernelSuTargets.mapValues { (abi, triple) ->
		tasks.register<Exec>("buildKernelSuLauncher" + triple.substringBefore('-').replaceFirstChar { it.uppercase() }) {
			workingDir(kernelSuSource)
			inputs.files(kernelSuSource.asFileTree.matching { include("Cargo.toml", "Cargo.lock", "src/**") })
			inputs.property("ndkVersion", providers.gradleProperty("clashNdkVersion"))
			outputs.file(kernelSuCargoDir.map { it.file("$triple/release/clash-tproxy") })
			val ndkDirectory = androidComponents.sdkComponents.ndkDirectory.get()
			environment("ANDROID_NDK_HOME", ndkDirectory.asFile.absolutePath)
			commandLine("cargo", "ndk", "--platform", "23", "-t", abi)
			args("rustc", "--release", "--locked", "--target-dir", kernelSuCargoDir.get().asFile.absolutePath)
			args("--", "-C", "link-arg=-Wl,-z,max-page-size=16384")
		}
	}

abstract class KernelSuModuleAssets : Sync() {
	@get:OutputDirectory
	abstract val assets: DirectoryProperty
}

androidComponents.onVariants { variant ->
	val stage =
		tasks.register<KernelSuModuleAssets>("prepare${variant.name.replaceFirstChar { it.uppercase() }}KernelSuModule") {
			from(kernelSuSource) {
				include("module.prop", "customize.sh", "skip_mount", "diagnostics.sh", "webroot/**")
				into("kernelsu")
			}
			for ((abi, launcher) in kernelSuLaunchers) {
				if (!phoneArm64 || variant.buildType != "debug" || abi == "arm64-v8a") {
					from(launcher) { into("kernelsu/bin/$abi") }
				}
			}
			into(assets)
		}
	variant.sources.assets?.addGeneratedSourceDirectory(stage, KernelSuModuleAssets::assets)
	if (variant.name == "release") {
		tasks.register<Zip>("packageKernelSuModule") {
			from(stage.flatMap { it.assets.dir("kernelsu") })
			archiveFileName.set("clash-android-tproxy.zip")
			destinationDirectory.set(rootProject.layout.buildDirectory.dir("kernelsu"))
		}
	}
}

// Keep unconfigured checkouts buildable and test data separate from production.
val firebaseRelease = file("src/release/google-services.json").isFile
val firebaseDebug = file("src/debug/google-services.json").isFile

val baseVersionName = "0.6.2"
val Project.verName: String get() = "${baseVersionName}$versionNameSuffix.${exec("git rev-parse --short HEAD")}"
val Project.verCode: Int get() = exec("git rev-list --count HEAD").toInt()
val Project.isDevVersion: Boolean
	get() =
		providers.environmentVariable("GITHUB_REF").orNull?.let { !it.startsWith("refs/tags/v") }
			?: exec("git tag --points-at HEAD --list v*").isEmpty()
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
	ndkVersion = providers.gradleProperty("clashNdkVersion").get()
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
		buildConfigField("boolean", "TESTING_CHANNEL", isDevVersion.toString())
		buildConfigField("String", "UPDATE_REPOSITORY", "\"${env("GITHUB_REPOSITORY") ?: "rust-proxy/clash-android"}\"")

		resValue("string", "app_name", if (keystore == null) "clash android dev" else "clash android")
		resValue("string", "app_ver", verName)

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		ndk {
			abiFilters.addAll(if (phoneArm64) setOf("arm64-v8a") else kernelSuTargets.keys)
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
			buildConfigField("boolean", "FIREBASE_ENABLED", firebaseRelease.toString())
			configure<CrashlyticsExtension> {
				mappingFileUploadEnabled = firebaseRelease && providers.gradleProperty("uploadFirebaseMapping").orNull == "true"
				nativeSymbolUploadEnabled = firebaseRelease
				unstrippedNativeLibsDir = files(project(":core").layout.buildDirectory.dir("rustJniLibs/android"))
			}
			isMinifyEnabled = true
			isShrinkResources = true
			if (keystore != null) {
				signingConfig = signingConfigs.getByName("release")
			}
		}
		debug {
			buildConfigField("boolean", "FIREBASE_ENABLED", firebaseDebug.toString())
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
		buildConfig = true
		compose = true
		resValues = true
	}
	splits {
		abi {
			isEnable = !phoneArm64 && env("ANDROID_SPLIT_ABI_ENABLE") == "true"
			reset()
			include("arm64-v8a", "armeabi-v7a", "x86_64")
			isUniversalApk = env("ANDROID_SPLIT_ABI_UNIVERSAL_APK") == "true"
		}
	}
}
kotlin {
	jvmToolchain(25)
}

tasks.matching { it.name == "generateCrashlyticsSymbolFileRelease" }.configureEach {
	dependsOn(":core:cargoBuild")
}

dependencies {
	implementation(platform(libs.firebase.bom))
	implementation(libs.firebase.analytics)
	implementation(libs.firebase.crashlytics.ndk)
	implementation(libs.protobuf.lite)
	implementation(libs.kotlinx.serialization.json)
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
	implementation(libs.androidx.material3.adaptive)
	implementation(libs.androidx.material3.navigation.suite)
	implementation(libs.androidx.material.icons.extended)
	implementation(libs.compose.destinations.core)

	ksp(libs.compose.destinations.ksp)

	testImplementation(libs.junit)
	testImplementation(libs.kotlinx.coroutines.test)
	androidTestImplementation(libs.androidx.junit)
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(platform(libs.androidx.compose.bom))
	androidTestImplementation(libs.androidx.ui.test.junit4)
	debugImplementation(libs.androidx.ui.tooling)
	debugImplementation(libs.androidx.ui.test.manifest)

	ktlintRuleset(libs.ktlint.compose.rules)
}
