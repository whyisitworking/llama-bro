plugins {
    alias(libs.plugins.android.library)
}

group = "io.github.whyisitworking"

// The OpenCL GPU backend (P2 T4, design §9): libllamabro-ggml-opencl.so with upstream's Adreno kernels, built in
// Qualcomm's image by sdk/native/scripts/build-backends.sh. Being on the classpath is enough (F61).
android {
    namespace = "com.suhel.llamabro.opencl"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main") {
        jniLibs.directories += projectDir.resolve("../native/build/android-backends/opencl/jniLibs").canonicalPath
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

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// built by the root project's buildBackends (sdk/native/scripts/build-backends.sh, Docker)
tasks.named("preBuild") { dependsOn(":buildBackends") }
