// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.android.library) apply false
	alias(libs.plugins.kotlin.android) apply false
	alias(libs.plugins.kotlin.compose) apply false
	alias(libs.plugins.rust.android) apply false
	alias(libs.plugins.ksp) apply false
	alias(libs.plugins.ktlint) apply false
}
val buildToolsVersion by extra("36.0.0")
val ndkVersion by extra("29.0.14206865")

allprojects {
	repositories {
		google()
		mavenCentral()
		// Android component of `rustls-platform-verifier`, published by the
		// rustls project on the `maven-archive` branch (see app/build.gradle.kts).
		maven {
			url = uri("https://github.com/rustls/rustls-platform-verifier/raw/maven-archive/android-release-support/maven/")
			content {
				includeGroup("org.rustls")
			}
		}
	}
}