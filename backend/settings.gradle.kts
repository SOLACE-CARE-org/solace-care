pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "solace"

val libraries = listOf(
    "event-envelope", "grpc-client", "error-model", "audit", "locale", "log-scrub", "service-security",
    "observability", "jobs", "core-settings", "reviewed-lists",
)
val services = listOf("core", "tracking", "ai", "gateway")

libraries.forEach { include(":libs:$it") }
services.forEach { include(":services:$it") }

// Java stubs generated from ../contracts/proto
include(":contracts")

// Keycloak extensions, loaded into Keycloak as provider jars
include(":keycloak:otp-authenticator")
