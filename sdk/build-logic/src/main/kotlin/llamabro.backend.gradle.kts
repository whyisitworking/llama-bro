// What the optional backend modules (hexagon, opencl) share: arm64-v8a only, the instrumentation runner, and the
// libraries `sdk/native/scripts/build-backends.sh` builds for them, wired in as jniLibs (and assets) once the module has
// said which directory is its own. Built by the root project's buildBackends before anything reads them.
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension

val backend = extensions.create<LlamaBroBackendExtension>("llamabroBackend")

plugins.withId("com.android.library") {
    extensions.configure<LibraryExtension> {
        // the NDK the native build uses (one file for the scripts, the workflows and this)
        ndkVersion = projectDir.resolve("../native/ndk.version").readText().trim()
        defaultConfig {
            ndk { abiFilters += "arm64-v8a" }
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        lint {
            disable += "ChromeOsAbiSupport"
        }
    }
    // the module's `llamabroBackend {}` block has run by the time the DSL is finalized
    extensions.configure<LibraryAndroidComponentsExtension> {
        finalizeDsl { android ->
            val name = backend.name.orNull ?: error("${project.path}: set llamabroBackend { name = ... }")
            val out = projectDir.resolve("../native/build/android-backends/$name").canonicalFile
            android.sourceSets.getByName("main") {
                jniLibs.directories += out.resolve("jniLibs").path
                if (backend.assets.get()) assets.directories += out.resolve("assets").path
            }
        }
    }
    tasks.named("preBuild") { dependsOn(":buildBackends") }
}
