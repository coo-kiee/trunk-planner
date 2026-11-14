// The only application module: routing, manifest and wiring. Rules stay in core and screens stay in the features.
import java.util.Properties
import java.util.UUID

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Test signing lives outside the repository and is not a distribution key. Generated on first use, so the documented
 * release command works by itself. Without the flag a release build stays unsigned on purpose.
 */
val useTestSigning = providers.gradleProperty("useTestSigning").orNull == "true"
val testSigningDir = rootDir.resolve("../../../test-signing").canonicalFile
val testSigning: Properties? = if (!useTestSigning) null else Properties().also { props ->
    val file = testSigningDir.resolve("signing.properties")
    val store = testSigningDir.resolve("cargo-test-release.jks")
    if (!file.exists() || !store.exists()) {
        testSigningDir.mkdirs()
        val secret = UUID.randomUUID().toString()
        val keytool = "${System.getProperty("java.home")}/bin/keytool"
        val exit = ProcessBuilder(
            keytool, "-genkeypair", "-keystore", store.path, "-storepass", secret, "-keypass", secret, "-alias", "cargo-test",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=Cargo Planner Test Signing Only",
        ).inheritIO().start().waitFor()
        check(exit == 0) { "keytool failed: $exit" }
        file.writeText("storePassword=$secret\nkeyAlias=cargo-test\n")
    }
    file.inputStream().use(props::load)
    props["storeFile"] = store.path
}

val demoRelayUrl = providers.gradleProperty("demoRelayUrl").orNull ?: "http://10.0.2.2:3210"
val demoPhoneUrl = providers.gradleProperty("demoPhoneUrl").orNull ?: "http://localhost:3211/"

android {
    namespace = "ai.cargo.planner"
    compileSdk = 35
    useLibrary("android.car")
    defaultConfig {
        applicationId = "ai.cargo.planner"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        testSigning?.let { props ->
            create("testRelease") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("storePassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (useTestSigning) signingConfig = signingConfigs.getByName("testRelease")
            buildConfigField("String", "SIGNING_KIND", if (useTestSigning) "\"TEST_SIGNING_NOT_FOR_DISTRIBUTION\"" else "\"UNSIGNED\"")
        }
        create("demoLocalDebug") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
            applicationIdSuffix = ".demolocal"
            versionNameSuffix = "-demolocal"
            buildConfigField("String", "SIGNING_KIND", "\"DEBUG\"")
            buildConfigField("String", "DEMO_RELAY_URL", "\"$demoRelayUrl\"")
            buildConfigField("String", "DEMO_PHONE_URL", "\"$demoPhoneUrl\"")
        }
    }
    testBuildType = "demoLocalDebug"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*") }
}
// The plain debug variant has no network policy decision, so it is not built at all.
androidComponents { beforeVariants { it.enable = it.buildType != "debug" } }
dependencies {
    implementation(project(":data"))
    implementation(project(":sync"))
    implementation(project(":vehicle-port"))
    implementation(project(":designsystem"))
    implementation(project(":render-gl"))
    implementation(project(":feature-library"))
    implementation(project(":feature-profile"))
    implementation(project(":feature-editor"))
    implementation(project(":feature-loading"))
    implementation(project(":feature-pairing"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.0")
}
