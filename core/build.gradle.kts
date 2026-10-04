import groovy.json.JsonSlurper
import java.io.File

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.rust.android)
}

android {
    namespace = "rs.clash.android.ffi"
    compileSdk = 37

    ndkVersion = rootProject.extra["ndkVersion"] as String
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
}

kotlin {
    jvmToolchain(25)
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.runtime)
    //noinspection Aligned16KB,UseTomlInstead
    implementation("net.java.dev.jna:jna:5.18.1@aar")


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

    extraCargoBuildArguments = arrayListOf("-p", "clash-android-ffi")

	// Kept as the bare command name (not the probed absolute path) so that a PATH entry
	// containing spaces cannot be mis-parsed by cargo; see the probe above for the gate.
	if (sccacheExecutable != null) {
		environmentalOverrides["RUSTC_WRAPPER"] = "sccache"
	}
	environmentalOverrides["RUSTC_BOOTSTRAP"] = "1"

	targets = listOf("arm64", "arm", "x86", "x86_64")
//	targets = listOf("arm64")
    // Switch to "release-dbg" to ship a build whose Rust panics print
    // symbolicated backtraces in logcat (defined in uniffi/Cargo.toml).
    profile = "release"
}

val rustJniLibsDir = layout.buildDirectory.dir("rustJniLibs/android").get()!!
tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }.configureEach {
    inputs.dir(rustJniLibsDir)
    dependsOn("cargoBuild")
}
