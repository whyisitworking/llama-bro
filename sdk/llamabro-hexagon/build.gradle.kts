plugins {
    alias(libs.plugins.android.library)
}

group = "io.github.whyisitworking"

// The NPU backend: libllamabro-ggml-hexagon.so and the HTP skeletons, built in Qualcomm's image
// by sdk/native/scripts/build-backends.sh. Being on the classpath is enough: the core finds it through the manifest.
android {
    namespace = "com.suhel.llamabro.hexagon"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main") {
        jniLibs.directories += backendsOut.resolve("jniLibs").path
        // the skeletons run on the DSP, which opens them from a file path: assets, copied out at start (F19, F36)
        assets.directories += backendsOut.resolve("assets").path
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        disable += "ChromeOsAbiSupport"
    }
}

kotlin {
    explicitApi()
    compilerOptions { optIn.add("com.suhel.llamabro.InternalLlamaBroApi") }
}

dependencies {
    api(project(":llamabro"))

    testImplementation(libs.junit)

    // the device-choice test needs both backends in one APK (until T10's `full` sample flavor)
    androidTestImplementation(project(":llamabro-opencl"))
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// built by the root project's buildBackends (sdk/native/scripts/build-backends.sh, Docker)
val backendsOut: File get() = projectDir.resolve("../native/build/android-backends/hexagon").canonicalFile
tasks.named("preBuild") { dependsOn(":buildBackends") }
