// Isolated SDK check only. Not the product app and has no product screen.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ai.cargo.portharness"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.cargo.portharness"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }
    // Same code with and without the normal permission, to observe the real missing-permission path.
    flavorDimensions += "permission"
    productFlavors {
        create("full") { dimension = "permission" }
        create("noperm") {
            dimension = "permission"
            applicationIdSuffix = ".noperm"
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":vehicle-port"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
