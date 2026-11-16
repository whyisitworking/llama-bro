import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    // for the tests only: AnnotatedToolboxTest runs a toolbox the real processor wrote (D61)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinx.bcv) apply false
}

group = "io.github.whyisitworking"

// Typed output and typed tools over kotlinx.serialization (D63): the schema converter and its annotations. A module of
// its own so that core's public API never mentions kotlinx.serialization.
android {
    namespace = "com.suhel.llamabro.typed"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
    }
}

kotlin {
    explicitApi()
    // typed tools register through core's raw registration seam (InternalLlamaBroApi, D61)
    compilerOptions { optIn.add("com.suhel.llamabro.InternalLlamaBroApi") }
}

dependencies {
    api(project(":llamabro"))
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":llamabro-testing"))
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

val bcvRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    description = "BCV's worker classpath (the same artifacts its plugin resolves)"
}
dependencies {
    bcvRuntime(libs.kotlinx.bcv)
    bcvRuntime(libs.bcv.asm)
    bcvRuntime(libs.bcv.asm.tree)
    bcvRuntime(libs.kotlin.metadata.jvm)
}

abstract class ReleaseApiBuild : KotlinApiBuildTask() {
    /** The release variant's class directories (Kotlin and Java); jars there are only R classes. */
    @get:org.gradle.api.tasks.Internal
    abstract val classDirs: ListProperty<Directory>

    @get:org.gradle.api.tasks.Internal
    abstract val classJars: ListProperty<RegularFile>

    init {
        inputClassesDirs.from(classDirs)
    }
}

val apiFile = layout.projectDirectory.file("api/llamabro-typed.api")
val apiBuild =
    tasks.register<ReleaseApiBuild>("apiBuild") {
        description = "Writes the release build's public API to build/api/llamabro-typed.api"
        outputApiFile.set(layout.buildDirectory.file("api/llamabro-typed.api"))
        runtimeClasspath.from(bcvRuntime)
    }
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.PROJECT)
            .use(apiBuild)
            .toGet(ScopedArtifact.CLASSES, ReleaseApiBuild::classJars, ReleaseApiBuild::classDirs)
    }
}
tasks.register<KotlinApiCompareTask>("apiCheck") {
    description = "Fails if the public API differs from api/llamabro-typed.api (apiDump after a reviewed change)"
    group = "verification"
    projectApiFile.set(apiFile)
    generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
}
tasks.register<Copy>("apiDump") {
    description = "Updates api/llamabro-typed.api from the release build"
    from(apiBuild.flatMap { it.outputApiFile })
    into(apiFile.asFile.parentFile)
}
