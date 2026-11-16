import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask

plugins {
    alias(libs.plugins.android.library)
    // only its task classes: the plugin wires itself to org.jetbrains.kotlin.android, which AGP 9 doesn't apply
    alias(libs.plugins.kotlinx.bcv) apply false
}

group = "io.github.whyisitworking"

android {
    namespace = "com.suhel.llamabro"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        ndk {
            abiFilters += "arm64-v8a"
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                // llama.cpp is always built optimised, also in the debug variant
                arguments +=
                    listOf(
                        "-DANDROID_STL=c++_shared",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DLB_LLAMA_DIR=${projectDir.resolve("../native/build/llama.cpp").canonicalPath}",
                    )
                // the CPU backends are loaded at runtime, not linked, so AGP packages them only when named
                // (variant list: llama.cpp ggml/src/CMakeLists.txt, GGML_CPU_ALL_VARIANTS on Android)
                targets += listOf("llamabro") +
                    listOf(
                        "android_armv8.0_1",
                        "android_armv8.2_1",
                        "android_armv8.2_2",
                        "android_armv8.6_1",
                        "android_armv9.0_1",
                        "android_armv9.2_1",
                        "android_armv9.2_2",
                    ).map { "ggml-cpu-$it" }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // run by sdk/scripts/check.sh (PA T1): any warning fails the build
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
        // arm64-v8a only, by design (design §9 "Platform")
        disable += "ChromeOsAbiSupport"
    }
}

kotlin {
    explicitApi()
    // the SDK uses its own engine seam freely; apps would have to opt in (InternalLlamaBroApi, D27)
    compilerOptions { optIn.add("com.suhel.llamabro.InternalLlamaBroApi") }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // @WorkerThread on blocking calls, so the app's lint flags them on the main thread
    api(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// The patched llama.cpp copy every native build compiles from (sdk/native/scripts/prepare-llama.sh).
val nativeDir = projectDir.resolve("../native")
val submoduleHead: File =
    providers
        .exec {
            commandLine("git", "-C", nativeDir.resolve("third_party/llama.cpp").path, "rev-parse", "--absolute-git-dir")
        }.standardOutput.asText
        .get()
        .trim()
        .let { File(it, "HEAD") }

val prepareLlama =
    tasks.register<Exec>("prepareLlama") {
        description = "Exports the llama.cpp submodule and applies sdk/native/patches into sdk/native/build/llama.cpp"
        inputs.dir(nativeDir.resolve("patches"))
        inputs.file(submoduleHead)
        inputs.file(nativeDir.resolve("scripts/prepare-llama.sh"))
        outputs.dir(nativeDir.resolve("build/llama.cpp"))
        commandLine("bash", nativeDir.resolve("scripts/prepare-llama.sh").path, nativeDir.resolve("build").path)
    }

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(prepareLlama)
}

// Host end-to-end (HostNativeTest), run by sdk/scripts/check.sh: Kotlin + JNI + the real core on this machine,
// compared with upstream llama-server. -Pllamabro.host.lib=<host bin dir> -Pllamabro.host.server=<url>
// -Pllamabro.host.model=<gguf> [-Pllamabro.host.mmproj=<gguf>]; HostEmbeddingTest also takes
// -Pllamabro.host.models=<LB_TEST_MODELS_DIR>, and HostNativeAttachmentTest -Pllamabro.host.clip=<wav> too. Without
// them the tests are skipped.
tasks.withType<Test>().configureEach {
    listOf("lib", "server", "model", "mmproj", "models", "clip").forEach { key ->
        providers.gradleProperty("llamabro.host.$key").orNull?.let { systemProperty("llamabro.host.$key", it) }
    }
    providers.gradleProperty("llamabro.host.lib").orNull?.let {
        jvmArgs("-Djava.library.path=$it")
        // HostNativeAttachmentTest reads a FileDescriptor's number, as an Android ParcelFileDescriptor gives it
        jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
        // the native runtime initialises once per process: a test of its first init needs a JVM of its own (D66)
        forkEvery = 1
    }
}

// Public API dump (PA T1, findings F45). `apiCheck` compares the release build's public API with the checked-in
// api/llamabro.api; `apiDump` updates it. BCV's plugin would set these up itself only for the
// org.jetbrains.kotlin.android plugin, which AGP 9's built-in Kotlin doesn't apply, so its tasks are wired here to
// the release variant's classes through AGP's variant API.
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

val apiFile = layout.projectDirectory.file("api/llamabro.api")
val apiBuild =
    tasks.register<ReleaseApiBuild>("apiBuild") {
        description = "Writes the release build's public API to build/api/llamabro.api"
        outputApiFile.set(layout.buildDirectory.file("api/llamabro.api"))
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
    description = "Fails if the public API differs from api/llamabro.api (run apiDump after a reviewed change)"
    group = "verification"
    projectApiFile.set(apiFile)
    generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
}
tasks.register<Copy>("apiDump") {
    description = "Updates api/llamabro.api from the release build"
    from(apiBuild.flatMap { it.outputApiFile })
    into(apiFile.asFile.parentFile)
}
