plugins {
    alias(libs.plugins.android.library)
}

group = "io.github.whyisitworking"

android {
    namespace = "com.suhel.llamabro"
    compileSdk = 36
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
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DLB_LLAMA_DIR=${rootDir.resolve("native/build/llama.cpp").path}",
                )
                // the CPU backends are loaded at runtime, not linked, so AGP packages them only when named
                // (variant list: llama.cpp ggml/src/CMakeLists.txt, GGML_CPU_ALL_VARIANTS on Android)
                targets += listOf("llamabro") + listOf(
                    "android_armv8.0_1", "android_armv8.2_1", "android_armv8.2_2", "android_armv8.6_1",
                    "android_armv9.0_1", "android_armv9.2_1", "android_armv9.2_2",
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
}

kotlin {
    explicitApi()
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
val nativeDir = rootDir.resolve("native")
val submoduleHead: File = providers.exec {
    commandLine("git", "-C", nativeDir.resolve("third_party/llama.cpp").path, "rev-parse", "--absolute-git-dir")
}.standardOutput.asText.get().trim().let { File(it, "HEAD") }

val prepareLlama = tasks.register<Exec>("prepareLlama") {
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
// -Pllamabro.host.model=<gguf> [-Pllamabro.host.mmproj=<gguf>]. Without them the test is skipped.
tasks.withType<Test>().configureEach {
    listOf("lib", "server", "model", "mmproj").forEach { key ->
        providers.gradleProperty("llamabro.host.$key").orNull?.let { systemProperty("llamabro.host.$key", it) }
    }
    providers.gradleProperty("llamabro.host.lib").orNull?.let { jvmArgs("-Djava.library.path=$it") }
}
