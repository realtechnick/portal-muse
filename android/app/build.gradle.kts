plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "dev.wobsoriano.museportal"
  compileSdk = 35

  defaultConfig {
    applicationId = "dev.wobsoriano.museportal"
    minSdk = 28
    targetSdk = 28
    versionCode = 1
    versionName = "0.1.0"
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  }

  // musecore runs as a child process, so libmusecore.so has to exist as a real file in
  // nativeLibraryDir rather than staying compressed inside the APK.
  packaging { jniLibs { useLegacyPackaging = true } }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}
