package com.suhel.llamabro

/**
 * A ggml backend and what became of it when the runtime started ([LlamaBro.backends]). `cpu` is always first and
 * always loaded; the others come from backend artifacts on the classpath (`llamabro-hexagon`, …). One that failed
 * never stops the runtime: it's here with its [error], and models load on the backends that did load.
 */
public class Backend internal constructor(
    /** `cpu`, or the name a backend artifact announces (`hexagon`, `opencl`, `vulkan`). */
    public val name: String,
    public val loaded: Boolean,
    /** Why it didn't load: ggml's or the dynamic linker's reason, or what its installer threw. Null when loaded. */
    public val error: String?,
    /** Names of the devices it added (e.g. `HTP0`), as in [DeviceInfo.name]. */
    public val devices: List<String>,
) {
    override fun equals(other: Any?): Boolean = other is Backend && parts() == other.parts()
    override fun hashCode(): Int = parts().hashCode()
    override fun toString(): String = "Backend(name=$name, loaded=$loaded, error=$error, devices=$devices)"

    private fun parts() = listOf(name, loaded, error, devices)
}
