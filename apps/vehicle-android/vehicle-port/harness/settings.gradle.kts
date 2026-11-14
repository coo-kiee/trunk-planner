// Independent build: references the real port module and copies none of its sources.
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
rootProject.name = "VehiclePortHarness"
include(":vehicle-port", ":harness-app")
project(":vehicle-port").projectDir = file("..")
project(":harness-app").projectDir = file("app")
