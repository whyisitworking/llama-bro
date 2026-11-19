// The demo's design-system rule as an Android Lint check (D113): the app and the design system take every size, colour
// and type role from the theme, and the app's screens are built from the design system's components.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.lint.api)
    compileOnly(libs.lint.checks)
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.tests)
    testImplementation(libs.junit)
}

tasks.jar {
    manifest { attributes("Lint-Registry-v2" to "com.suhel.llamabro.demo.lint.DesignIssueRegistry") }
}
