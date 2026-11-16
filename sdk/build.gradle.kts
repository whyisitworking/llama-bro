plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // llamabro-typed-ksp is a JVM module, and llamabro-typed runs it in its tests; declared here, as in the root build,
    // so both builds compile with the catalog's Kotlin (AGP alone brings an older one, which can't read the KSP API's)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

apply(from = "build-backends.gradle.kts")
