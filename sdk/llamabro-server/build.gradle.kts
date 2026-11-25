plugins {
    alias(libs.plugins.android.library)
    id("llamabro.library")
    id("llamabro.api-check")
    alias(libs.plugins.kotlin.serialization)
    id("llamabro.publish")
}

description = "Serves a loaded Llama Bro model over HTTP, with llama-server's API."

// A Ktor CIO server in front of a loaded Model (docs/design/server.md): the engine is pure Kotlin, so the module's
// logic moves to Kotlin Multiplatform with the rest; Android API stays out of it.
android {
    namespace = "com.suhel.llamabro.server"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":llamabro"))
    // ServerConfig.workDir is a kotlinx-io Path
    api(libs.kotlinx.io.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.cors)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":llamabro-testing"))
    testImplementation(testFixtures(project(":llamabro")))
    testImplementation(libs.ktor.server.test.host)
}

// the tests send CORS requests with HttpURLConnection, which drops Origin and Access-Control-Request-* unless told not to
tasks.withType<Test>().configureEach {
    systemProperty("sun.net.http.allowRestrictedHeaders", "true")
}
