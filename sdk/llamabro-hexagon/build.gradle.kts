plugins {
    alias(libs.plugins.android.library)
    id("llamabro.library")
    id("llamabro.backend")
    id("llamabro.publish")
}

description = "The optional Qualcomm Hexagon NPU backend for Llama Bro."

// The NPU backend: libllamabro-ggml-hexagon.so and the HTP skeletons, built in Qualcomm's image
// by sdk/native/scripts/build-backends.sh. Being on the classpath is enough: the core finds it through the manifest.
android {
    namespace = "com.suhel.llamabro.hexagon"
}

llamabroBackend {
    name = "hexagon"
    // the skeletons run on the DSP, which opens them from a file path: assets, copied out at start (F19, F36)
    assets = true
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
