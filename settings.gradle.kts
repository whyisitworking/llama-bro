pluginManagement {
    repositories {
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
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Llama Bro"
// The repository root is one Gradle build over the SDK's modules and samples (listed once in sdk/modules.gradle.kts), so
// Android Studio opens the root and finds them. The SDK is also its own build, `./gradlew -p sdk` (the phase checks and
// the demo's includeBuild use that); both are the same projects over the same folders. The demo app is its own build.
apply(from = "sdk/modules.gradle.kts")
