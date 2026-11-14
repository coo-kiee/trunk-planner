// Pure immutable scene commands, revision hashing and save guards. No IO, clock or Android access.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-library`
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core-geometry"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.0.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.test {
    useJUnit()
    systemProperty("cargo.productRoot", rootDir.resolve("../..").canonicalPath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
