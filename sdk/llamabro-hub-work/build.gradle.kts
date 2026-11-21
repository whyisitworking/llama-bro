plugins {
    alias(libs.plugins.android.library)
    id("llamabro.library")
    id("llamabro.api-check")
    id("llamabro.publish")
}

description = "A WorkManager worker that runs Llama Bro's hub downloads in the background."

android {
    namespace = "com.suhel.llamabro.hub.work"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    testOptions {
        // The JVM tests run the worker through work-testing with no device and no Robolectric (the repo has none): the
        // android.jar stubs only need to return defaults for the logging and Build calls on that path.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin { explicitApi() }

dependencies {
    // The worker lives apart from the hub so that the hub doesn't depend on androidx.work (D64, D75).
    api(project(":llamabro-hub"))
    api(libs.androidx.work.runtime.ktx)
    api(libs.androidx.core.ktx) // NotificationCompat.Builder is in DownloadNotifications' API
    testImplementation(project(":llamabro-hub-testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.work.testing)
}
