plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.suhel.llamabro.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.suhel.llamabro.sample"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { buildConfig = true }

    // one flavor per set of backend artifacts an app can ship (P2 T10, D31); the same applicationId, so the models
    // pushed into the app's files survive switching flavors. AUTO_DEVICE is what DevicePolicy.Auto must pick.
    flavorDimensions += "backends"
    productFlavors {
        create("cpu") {
            dimension = "backends"
            buildConfigField("String", "AUTO_DEVICE", "\"Cpu\"")
            buildConfigField("boolean", "FULL_SUITE", "false")
        }
        create("gpu") {
            dimension = "backends"
            buildConfigField("String", "AUTO_DEVICE", "\"Gpu\"")
            buildConfigField("boolean", "FULL_SUITE", "false")
        }
        create("full") {
            dimension = "backends"
            buildConfigField("String", "AUTO_DEVICE", "\"Npu\"")
            buildConfigField("boolean", "FULL_SUITE", "true")
        }
        // the forced failure: full, but src/noskel/assets overrides the HTP v79 skeleton with an empty file (app assets
        // win over a library's), so the skeleton check fails and Auto must say why and run on the GPU
        create("noskel") {
            dimension = "backends"
            buildConfigField("String", "AUTO_DEVICE", "\"Gpu\"")
            buildConfigField("boolean", "FULL_SUITE", "false")
        }
    }

    buildTypes {
        release {
            // minified like a real app's release build: proves llamabro's consumer R8 rules (findings F28)
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // public API only: this app must compile without internal access (P1 T12)
    implementation(project(":llamabro"))
    "gpuImplementation"(project(":llamabro-opencl"))
    "fullImplementation"(project(":llamabro-hexagon"))
    "fullImplementation"(project(":llamabro-opencl"))
    "noskelImplementation"(project(":llamabro-hexagon"))
    "noskelImplementation"(project(":llamabro-opencl"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
