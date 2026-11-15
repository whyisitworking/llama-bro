import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlinx.bcv) apply false
}

group = "io.github.whyisitworking"

android {
    namespace = "com.suhel.llamabro.hub.work"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // The JVM tests run the worker through work-testing with no device and no Robolectric (the repo has none): the
        // android.jar stubs only need to return defaults for the logging and Build calls on that path.
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
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

val apiFile = layout.projectDirectory.file("api/llamabro-hub-work.api")
val apiBuild =
    tasks.register<ReleaseApiBuild>("apiBuild") {
        description = "Writes the release build's public API to build/api/llamabro-hub-work.api"
        outputApiFile.set(layout.buildDirectory.file("api/llamabro-hub-work.api"))
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
    description = "Fails if the public API differs from api/llamabro-hub-work.api (run apiDump after a reviewed change)"
    group = "verification"
    projectApiFile.set(apiFile)
    generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
}
tasks.register<Copy>("apiDump") {
    description = "Updates api/llamabro-hub-work.api from the release build"
    from(apiBuild.flatMap { it.outputApiFile })
    into(apiFile.asFile.parentFile)
}
