plugins {
    alias(libs.plugins.android.library)
    id("llamabro.library")
    id("llamabro.api-check")
    alias(libs.plugins.kotlin.serialization)
    // for the tests only: AnnotatedToolboxTest runs a toolbox the real processor wrote (D61)
    alias(libs.plugins.ksp)
    id("llamabro.publish")
}

description = "Typed output and typed tools for Llama Bro, over kotlinx.serialization."

// Typed output and typed tools over kotlinx.serialization (D63): the schema converter and its annotations. A module of
// its own so that core's public API never mentions kotlinx.serialization.
android {
    namespace = "com.suhel.llamabro.typed"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":llamabro"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":llamabro-testing"))
    testImplementation(testFixtures(project(":llamabro")))
    kspTest(project(":llamabro-typed-ksp"))
}

// Host end-to-end (HostTypedTest), run by sdk/scripts/check.sh after core's: typed output over the real core on this
// machine. -Pllamabro.host.lib=<host bin dir> -Pllamabro.host.model=<gguf>; without them the test is skipped.
tasks.withType<Test>().configureEach {
    listOf("lib", "model").forEach { key ->
        providers.gradleProperty("llamabro.host.$key").orNull?.let { systemProperty("llamabro.host.$key", it) }
    }
    providers.gradleProperty("llamabro.host.lib").orNull?.let { jvmArgs("-Djava.library.path=$it") }
}
