plugins {
    alias(libs.plugins.android.library)
    id("llamabro.library")
    id("llamabro.api-check")
    id("llamabro.publish")
}

description = "Model search and resumable downloads from the Hugging Face Hub for Llama Bro."

android {
    namespace = "com.suhel.llamabro.hub"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    explicitApi()
    // the transport and storage seam is public only for llamabro-hub-testing (InternalLlamaBroApi, D27, D75)
    compilerOptions {
        optIn.add("com.suhel.llamabro.InternalLlamaBroApi")
        // a data class's copy is as visible as its constructor, so only the SDK makes the values it reports (D102)
        freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
    }
}

dependencies {
    api(project(":llamabro"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

tasks.withType<Test>().configureEach {
    systemProperty("llamabro.hub.fake", projectDir.resolve("../scripts/hub-fake/fake_hub.py").canonicalPath)
    listOf("golden.dir", "model.dir").forEach { key ->
        providers.gradleProperty("llamabro.hub.$key").orNull?.let {
            systemProperty("llamabro.hub.$key", it)
        }
    }
}
