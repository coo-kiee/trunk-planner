// Pure JVM geometry authority. Consumes the generated wire DTOs read-only and exposes them to dependents.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-library`
}
kotlin { jvmToolchain(17) }
sourceSets.main { kotlin.srcDir("../../../packages/contracts/kotlin") }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test {
    useJUnit()
    systemProperty("cargo.productRoot", rootDir.resolve("../..").canonicalPath)
    systemProperty("cargo.moduleRoot", projectDir.canonicalPath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
