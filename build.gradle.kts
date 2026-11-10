// The repository's root build: the SDK's modules and samples (see settings.gradle.kts). Same as sdk/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

apply(from = "sdk/build-backends.gradle.kts")
