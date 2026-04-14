pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// foojay-resolver lets Gradle auto-download missing JDK toolchains (e.g. Java 17
// for the IntelliJ Platform 2024.1 baseline) instead of failing the build with
// "no matching toolchain found" when a developer's machine has a different JDK.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "rally-plugin"
