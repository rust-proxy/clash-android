plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.ktlint)
}

android {
	namespace = "rs.clash.traffictest"
	compileSdk = 37
	buildToolsVersion = rootProject.extra["buildToolsVersion"] as String
	defaultConfig {
		applicationId = "rs.clash.traffictest"
		minSdk = 29
		targetSdk = 37
		versionCode = 1
		versionName = "1.0"
	}
	buildFeatures { compose = true }
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_25
		targetCompatibility = JavaVersion.VERSION_25
	}
}

kotlin { jvmToolchain(25) }

dependencies {
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.material3)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	ktlintRuleset(libs.ktlint.compose.rules)
}
