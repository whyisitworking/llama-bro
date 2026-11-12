import org.gradle.api.attributes.Bundling

// The repository's root build: the SDK's modules, its acceptance apps and the demo app (see settings.gradle.kts).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.detekt) apply false
}

apply(from = "sdk/build-backends.gradle.kts")

// Style and structure checks, run from here (the SDK's modules are in this build too):
//   ktlint: formatting and style, configured by .editorconfig (the command-line tool, pinned in the version catalog;
//           the Gradle plugin finds no Kotlin sources under AGP 9's built-in Kotlin)
//   detekt: smells, complexity, naming and structure, configured by config/detekt/detekt.yml
// `./gradlew qualityCheck` runs both; `./gradlew ktlintFormat` fixes what ktlint can.
val ktlint by configurations.creating {
    // the tool's self-contained jar, not its dozens of modules
    attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
}
dependencies { ktlint(libs.ktlint.cli) }

val ktlintSources =
    listOf(
        "**/src/**/*.kt",
        "**/*.gradle.kts",
        "!**/build/**",
        "!sdk/native/**",
        "!research/**",
        "!**/generated/**",
    )

fun registerKtlint(name: String, description: String, vararg extra: String) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        this.description = description
        classpath = ktlint
        mainClass.set("com.pinterest.ktlint.Main")
        args(ktlintSources + extra)
    }

val ktlintCheck = registerKtlint("ktlintCheck", "Checks the style of every Kotlin file.")
registerKtlint("ktlintFormat", "Fixes the style of every Kotlin file where ktlint can.", "--format")

subprojects {
    apply(plugin = "dev.detekt")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        parallel.set(true)
    }
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "ktlint and detekt on every module."
    dependsOn(ktlintCheck, subprojects.map { "${it.path}:detekt" })
}
