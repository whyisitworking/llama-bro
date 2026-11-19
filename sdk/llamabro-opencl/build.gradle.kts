plugins {
    alias(libs.plugins.android.library)
    id("llamabro.library")
}

group = "io.github.whyisitworking"

// The OpenCL GPU backend: libllamabro-ggml-opencl.so with upstream's Adreno kernels, built in
// Qualcomm's image by sdk/native/scripts/build-backends.sh. Being on the classpath is enough (F61).
android {
    namespace = "com.suhel.llamabro.opencl"

    defaultConfig {
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main") {
        jniLibs.directories += projectDir.resolve("../native/build/android-backends/opencl/jniLibs").canonicalPath
    }

    lint {
        disable += "ChromeOsAbiSupport"
    }
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

// built by the root project's buildBackends (sdk/native/scripts/build-backends.sh, Docker)
tasks.named("preBuild") { dependsOn(":buildBackends") }
