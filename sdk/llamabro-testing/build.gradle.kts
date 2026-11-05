plugins {
    alias(libs.plugins.android.library)
}

group = "io.github.whyisitworking"

// FakeLlamaBro for apps' JVM tests (PA T7, D27): the real llamabro Kotlin layer over a scripted engine, no native code
android {
    namespace = "com.suhel.llamabro.testing"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
    }
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
