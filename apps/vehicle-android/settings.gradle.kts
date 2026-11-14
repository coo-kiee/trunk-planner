pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
    }
}
rootProject.name = "CargoPlanner"
include(":foundation-smoke", ":contract-jvm")
project(":foundation-smoke").projectDir = file("../../tools/product-foundation/foundation-smoke")
project(":contract-jvm").projectDir = file("../../tools/product-foundation/contract-jvm")
// Register sibling modules only when their owner supplies a build, never placeholder features.
listOf("core-geometry", "core-scene", "data", "vehicle-port", "sync", "designsystem", "render-gl",
    "feature-library", "feature-profile", "feature-editor", "feature-loading", "feature-pairing", "app")
    .filter { file("$it/build.gradle.kts").isFile }.forEach { include(":$it") }
