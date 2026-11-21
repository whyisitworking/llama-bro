plugins {
    kotlin("jvm")
    id("llamabro.publish")
}

description = "The KSP processor for Llama Bro's typed tools: turns an @LbToolbox class into a toolbox."

// The annotation processor for typed tools (D61, D63): it turns an @LbToolbox class into an asToolbox() extension
// built on llamabro-typed's tool(…). A JVM library that apps add with `ksp(…)`; it reads the annotations by name, so it
// does not depend on the Android llamabro-typed, and writes code with its own small writer (no KotlinPoet).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.ksp.api)

    // kctfork compiles snippets with this processor, to test its build errors (D81); never shipped
    testImplementation(libs.kctfork.ksp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.core)
}
