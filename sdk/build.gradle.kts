plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // llamabro-typed-ksp is a JVM module, and llamabro-typed runs it in its tests; declared here, as in the root build,
    // so both builds compile with the catalog's Kotlin (AGP alone brings an older one, which can't read the KSP API's)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    // the publish plugin's upload service is shared by every published module; loaded here once, it has one class for
    // all of them. Left to each module, llamabro-typed (whose own plugins give it a class loader of its own) loaded a
    // second copy and publishToMavenCentral failed before uploading anything
    id("llamabro.publish") apply false
}

apply(from = "build-backends.gradle.kts")
