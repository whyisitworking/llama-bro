import org.gradle.api.provider.Property

/** What a backend module says about itself to the `llamabro.backend` convention: `llamabroBackend { name = "opencl" }`. */
abstract class LlamaBroBackendExtension {
    /** The directory under `sdk/native/build/android-backends` that `build-backends.sh` fills for this module. */
    abstract val name: Property<String>

    /** Whether that directory also has `assets` (the Hexagon skeletons, which the DSP opens from a file path). */
    abstract val assets: Property<Boolean>

    init {
        assets.convention(false)
    }
}
