// Room persistence. The only module of this owner with external effects; pure rules stay in core-scene.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
android {
    namespace = "ai.cargo.data"
    compileSdk = 35
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets["androidTest"].assets.srcDir("schemas")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    api(project(":core-scene"))
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.sqlite:sqlite-framework:2.4.0")
    ksp("androidx.room:room-compiler:2.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.0")
}
