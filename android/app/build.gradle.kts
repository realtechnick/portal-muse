plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
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
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
  implementation(project(":muse"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  testImplementation(libs.junit)
}
