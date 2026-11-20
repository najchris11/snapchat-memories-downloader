pluginManagement {
    repositories {
        // Only Google's own plugins (AGP) live here. Unfiltered, every plugin marker -
        // foojay, Kotlin, Compose, detekt - is looked up on dl.google.com before the
        // portal. Gradle falls through only on a clean 404; any other error from a listed
        // repository aborts resolution, so each extra host is another way to fail a cold
        // build.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "snapchat-memories-downloader"
include(":composeApp")
