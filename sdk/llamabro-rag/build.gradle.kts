plugins {
    alias(libs.plugins.android.library)
    id("llamabro.library")
    id("llamabro.api-check")
    alias(libs.plugins.kotlin.serialization)
    id("llamabro.publish")
}

description = "Retrieval for Llama Bro: chunk, embed, store and search documents, and a search tool for a chat."

// Retrieval-augmented generation over a loaded embedder (docs/design/rag.md). Pure Kotlin on Okio and
// kotlinx.serialization, so its logic moves to Kotlin Multiplatform with the rest; Android API stays out of it.
android {
    namespace = "com.suhel.llamabro.rag"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":llamabro"))
    api(project(":llamabro-typed"))
    api(libs.okio)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okio.fakefilesystem)
    testImplementation(project(":llamabro-testing"))
    testImplementation(testFixtures(project(":llamabro")))
}
