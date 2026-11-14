// Only this module adds the generated token sources and re-exports them. Consumers must not add that srcDir again.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "ai.cargo.designsystem"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    sourceSets["main"].java.srcDir("../../../packages/design-tokens/kotlin")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api("androidx.compose.foundation:foundation:1.7.0")
    api("androidx.compose.ui:ui:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.0")
}
