plugins {
    alias(libs.plugins.android.library)
    id("llamabro.library")
    id("llamabro.api-check")
    id("llamabro.publish")
}

description =
    "Runs llama.cpp's llama-server core in-process on Android behind a Kotlin API: chat, conversations, tools, media, embeddings and speech."

android {
    namespace = "com.suhel.llamabro"
    ndkVersion = projectDir.resolve("../native/ndk.version").readText().trim() // the SDK's one NDK pin

    defaultConfig {
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

    // LlamaBro.withHostEngine for other modules' host tests (llamabro-typed's HostTypedTest): kept out of the release AAR
    testFixtures {
        enable = true
    }

    // run by sdk/scripts/check.sh: any warning fails the build
    lint {
        // arm64-v8a only, by design
        disable += "ChromeOsAbiSupport"
    }
}

kotlin {
    explicitApi()
    // the SDK uses its own engine seam freely; apps would have to opt in (InternalLlamaBroApi, D27)
    compilerOptions {
        optIn.add("com.suhel.llamabro.InternalLlamaBroApi")
        // a data class's copy is as visible as its constructor, so only the SDK makes the values it reports (D102)
        freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
    }
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
