plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.suhel.llamabro.demo.benchmark"
    compileSdk = 37

    defaultConfig {
        // the app under test is com.suhel.llamabro.demo.benchmark (D149's suffix): these tests sit beside it
        testApplicationId = "com.suhel.llamabro.demo.benchmark.test"
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":demo:app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
