pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "consenzy-example-android"

include(":app")

// Vendored Consenzy Android SDK (Apache-2.0), until it is on Maven Central. See vendor/consenzy-android/README.md.
include(":consenzy-core")
project(":consenzy-core").projectDir = file("vendor/consenzy-android/core")
include(":consenzy-ui")
project(":consenzy-ui").projectDir = file("vendor/consenzy-android/ui")
