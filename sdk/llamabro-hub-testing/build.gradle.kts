plugins {
    alias(libs.plugins.android.library)
    id("llamabro.library")
    id("llamabro.api-check")
}

group = "io.github.whyisitworking"

// FakeLlamaBroHub for apps' JVM tests (D70, D75): the real hub over a scripted Hub and disk. A module of its own so
// that apps without the hub never get it, as llamabro-testing is for the core (D27).
android {
    namespace = "com.suhel.llamabro.hub.testing"
}

kotlin {
    explicitApi()
    // built on the hub's transport seam, which apps would have to opt in to (InternalLlamaBroApi, D27)
    compilerOptions { optIn.add("com.suhel.llamabro.InternalLlamaBroApi") }
}

dependencies {
    api(project(":llamabro-hub"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":llamabro-testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
