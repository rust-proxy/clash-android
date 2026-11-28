import java.io.File
import net.mullvad.androidrust.CargoBuildTask
import rs.clash.build.CargoWorkspace

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.rust.android)
}

android {
    namespace = "rs.clash.android.ffi"
    compileSdk = 37

	ndkVersion = providers.gradleProperty("clashNdkVersion").get()
    buildToolsVersion = rootProject.extra["buildToolsVersion"] as String
    defaultConfig {
        minSdk = 23
		compileSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        compose = true
    }
	// Older incremental builds can still have x86 libraries in rustJniLibs.
	packaging.jniLibs.excludes += "**/x86/*.so"
}

kotlin {
    jvmToolchain(25)
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.runtime)
    //noinspection Aligned16KB,UseTomlInstead
    implementation("net.java.dev.jna:jna:5.19.1@aar")


    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// sccache is optional tooling: it is only handed to cargo as the compiler wrapper when it is
// actually resolvable on PATH (probed once here, at configuration time). Setting
// RUSTC_WRAPPER unconditionally breaks builds on machines without sccache, where cargo fails
// with a confusing "could not execute process `sccache`" style error instead of just building.
// The probe mirrors how cargo resolves the wrapper: the first match in PATH wins.
val sccacheExecutable: File? = run {
    val isWindowsHost = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    val fileNames = if (isWindowsHost) {
        listOf("sccache.exe", "sccache.cmd", "sccache.bat", "sccache")
    } else {
        listOf("sccache")
    }
    System.getenv("PATH").orEmpty()
        .split(File.pathSeparatorChar)
        .asSequence()
        .map { it.trim().trim('"') }
        .filter { it.isNotEmpty() }
        .flatMap { dir -> fileNames.asSequence().map { name -> File(dir, name) } }
        .firstOrNull { it.isFile }
}

if (sccacheExecutable != null) {
    logger.info("sccache found at {}: setting RUSTC_WRAPPER=sccache", sccacheExecutable.absolutePath)
} else {
    logger.info("sccache not found on PATH: building without RUSTC_WRAPPER")
}

cargo {
    module  = "../uniffi"  // Directory containing Cargo.toml
	libname = "clash_android_ffi"
	apiLevel = android.defaultConfig.minSdk

    extraCargoBuildArguments = arrayListOf("-p", "clash-android-ffi")

	// Kept as the bare command name (not the probed absolute path) so that a PATH entry
	// containing spaces cannot be mis-parsed by cargo; see the probe above for the gate.
	if (sccacheExecutable != null) {
		environmentalOverrides["RUSTC_WRAPPER"] = "sccache"
	}
	environmentalOverrides["RUSTC_BOOTSTRAP"] = "1"

	val phoneArm64 = providers.gradleProperty("phoneArm64").map(String::toBoolean).getOrElse(false)
	targets = if (phoneArm64) listOf("arm64") else listOf("arm64", "arm", "x86_64")
    // Cargo keeps line tables here. AGP strips the APK copy; CI archives the originals.
    profile = "release"
}

val ffiTargetDir = CargoWorkspace.targetDirectory(providers, rootProject.layout.projectDirectory.dir("uniffi"))

// The Rust plugin assigns each task's target and environment after project evaluation.
afterEvaluate {
	// Preserve the plugin's local.properties override, resolved relative to :core.
	val localTarget = cargo.localProperties.getProperty("rust.cargoTargetDir")?.let { file(it).absolutePath }
	tasks.withType<CargoBuildTask>().configureEach {
		if (localTarget != null) {
			target.set(localTarget)
			environmentOverrides.put("CARGO_TARGET_DIR", localTarget)
		} else {
			// The plugin resolves relative paths from :core, while Cargo runs in uniffi.
			target.set(ffiTargetDir.map { it.absolutePath })
		}
		environmentOverrides.put("ANDROID_NDK_HOME", ndk.map { it.path.absolutePath })
		environmentOverrides.put("CARGO_ENCODED_RUSTFLAGS", providers.provider {
			// cronet-sys searches the unversioned NDK directory for libc++_static.a.
			// Put the API's shared libc stubs ahead of the static libc.a beside it.
			val tools = ndk.get().path.resolve("toolchains/llvm/prebuilt").listFiles()!!.single { it.isDirectory }
			val stubs = tools.resolve("sysroot/usr/lib/${toolchain.get().binutilsTriple}/${apiLevel.get()}")
			require(stubs.resolve("libc.so").isFile) { "Missing Android API stubs in $stubs" }
			val inherited = System.getenv("CARGO_ENCODED_RUSTFLAGS")?.split('\u001f')
				?: System.getenv("RUSTFLAGS").orEmpty().split(' ').filter(String::isNotBlank)
			(inherited + "-Lnative=${stubs.absolutePath}").joinToString("\u001f")
		})
	}
}

val rustJniLibsDir = layout.buildDirectory.dir("rustJniLibs/android").get()!!
tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }.configureEach {
    inputs.dir(rustJniLibsDir)
    dependsOn("cargoBuild")
}
