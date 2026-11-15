import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlinx.bcv) apply false
}

group = "io.github.whyisitworking"

// FakeLlamaBroHub for apps' JVM tests (D70, D75): the real hub over a scripted Hub and disk. A module of its own so
// that apps without the hub never get it, as llamabro-testing is for the core (D27).
android {
    namespace = "com.suhel.llamabro.hub.testing"
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
    // built on the hub's transport seam, which apps would have to opt in to (InternalLlamaBroApi, D27)
    compilerOptions { optIn.add("com.suhel.llamabro.InternalLlamaBroApi") }
}

dependencies {
    api(project(":llamabro-hub"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":llamabro-testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
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

val apiFile = layout.projectDirectory.file("api/llamabro-hub-testing.api")
val apiBuild =
    tasks.register<ReleaseApiBuild>("apiBuild") {
        description = "Writes the release build's public API to build/api/llamabro-hub-testing.api"
        outputApiFile.set(layout.buildDirectory.file("api/llamabro-hub-testing.api"))
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
    description = "Fails if the public API differs from api/llamabro-hub-testing.api (apiDump after a reviewed change)"
    group = "verification"
    projectApiFile.set(apiFile)
    generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
}
tasks.register<Copy>("apiDump") {
    description = "Updates api/llamabro-hub-testing.api from the release build"
    from(apiBuild.flatMap { it.outputApiFile })
    into(apiFile.asFile.parentFile)
}
