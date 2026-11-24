plugins {
    alias(libs.plugins.android.library)
    id("llamabro.library")
    id("llamabro.backend")
    id("llamabro.publish")
}

description = "The optional OpenCL (Adreno GPU) backend for Llama Bro."

// The OpenCL GPU backend: libllamabro-ggml-opencl.so with upstream's Adreno kernels, built in
// Qualcomm's image by sdk/native/scripts/build-backends.sh. Being on the classpath is enough (F61).
android {
    namespace = "com.suhel.llamabro.opencl"
}

llamabroBackend {
    name = "opencl"
}

kotlin {
    explicitApi()
    compilerOptions { optIn.add("com.suhel.llamabro.InternalLlamaBroApi") }
}

dependencies {
    api(project(":llamabro"))

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
