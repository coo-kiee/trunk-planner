// SDK effect boundary. No product UI, no Room: the port cannot reach a save path by construction.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ai.cargo.vehicleport"
    compileSdk = 35
    useLibrary("android.car")
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // implementation on purpose: consumers never see an SDK type.
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.0")
}
