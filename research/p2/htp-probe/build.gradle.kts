plugins { id("com.android.application") version "9.1.0" }

android {
    namespace = "com.suhel.htpprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.suhel.htpprobe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        // T1 Step 3: an ordinary debuggable app and a non-debuggable release build
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // build/probe-libs: the spike's P libraries plus libprobe.so (prepare.sh); assets: the HTP skeleton
    sourceSets.getByName("main") {
        jniLibs.directories += "build/probe-libs"
        assets.directories += "build/probe-assets"
    }
}
