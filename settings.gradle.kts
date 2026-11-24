pluginManagement {
    // the SDK's convention plugins (sdk/build-logic)
    includeBuild("sdk/build-logic")
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
// The repository root is the one Gradle build Android Studio opens: the SDK's modules and acceptance apps (listed once
// in sdk/modules.gradle.kts) and the demo app (demo/). The SDK is also its own build, `./gradlew -p sdk`, which the
// SDK's checks use; it is the same projects over the same folders and does not know the demo.
apply(from = "sdk/modules.gradle.kts")
include(":demo:app", ":demo:designsystem", ":demo:benchmark", ":demo:lint")
