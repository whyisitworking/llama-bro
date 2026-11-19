plugins {
    alias(libs.plugins.android.library)
    id("llamabro.library")
    id("llamabro.api-check")
}

group = "io.github.whyisitworking"

// FakeLlamaBro for apps' JVM tests (D27): the real llamabro Kotlin layer over a scripted engine, no native code
android {
    namespace = "com.suhel.llamabro.testing"
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":llamabro"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
