// A build of its own rather than a module of the main one, because a plugin
// has to be built before the build that applies it can be configured. The
// root build includes it (see settings.gradle.kts there), which is what lets
// `example` apply `dev.surefold.pelican` by id and exercise the real consumer path.

pluginManagement {
    repositories {
        // Google's mirror of Central first: Central answers GitHub's shared runners 429 often enough to fail CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "centralMirror"
            mavenContent { releasesOnly() }
        }
        gradlePluginPortal()
    }
}

// This build provisions its own toolchain, so it declares its own resolver;
// settings of an included build do not inherit the including build's.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pelican-gradle-plugin"

dependencyResolutionManagement {
    repositories {
        // Google's mirror of Central first: Central answers GitHub's shared runners 429 often enough to fail CI.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            name = "centralMirror"
            mavenContent { releasesOnly() }
        }
        mavenCentral()
    }
}
