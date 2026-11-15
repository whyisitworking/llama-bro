package com.suhel.llamabro.internal

import com.suhel.llamabro.Backend
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.LoadReport

/** Turns a [DevicePolicy] into the devices to try, in order (P2 T6). Pure: the runtime does the loading. */
internal object DeviceResolver {
    /** One device to try: CPU has no device list ([devices] empty), the others exactly one. */
    data class Candidate(val kind: DeviceKind, val backend: String, val deviceName: String) {
        val devices: List<String> get() = if (kind == DeviceKind.Cpu) emptyList() else listOf(deviceName)
    }

    class Plan(val candidates: List<Candidate>, val skipped: List<LoadReport.Attempt>)

    /** Which kind a backend serves (D32). Vulkan, if it comes back, is another GPU route (D31). */
    fun kindOf(backend: String): DeviceKind? =
        when (backend) {
            "cpu" -> DeviceKind.Cpu
            "hexagon" -> DeviceKind.Npu
            "opencl", "vulkan" -> DeviceKind.Gpu
            else -> null
        }

    private val autoOrder = listOf(DeviceKind.Npu, DeviceKind.Gpu, DeviceKind.Cpu)

    /**
     * Where the encoder runs ([com.suhel.llamabro.ModelConfig.encoderDevice]): null follows [model]; `Only` is that
     * kind, or null when it's unavailable (the caller throws); `Prefer` is that kind or [model]; `Auto` the first
     * available.
     */
    fun encoder(policy: DevicePolicy?, model: Candidate, backends: List<Backend>): Candidate? {
        if (policy == null) return model
        val first = plan(policy, backends).candidates.firstOrNull()
        return when (policy) {
            is DevicePolicy.Only -> first
            is DevicePolicy.Prefer -> first?.takeIf { it.kind == policy.kind } ?: model
            DevicePolicy.Auto -> first ?: model
        }
    }

    /** The `mmproj_device` config value for [encoder] when it differs from [model]'s device, else null. */
    fun encoderConfig(encoder: Candidate, model: Candidate): String? =
        when {
            encoder.deviceName == model.deviceName -> null
            encoder.kind == DeviceKind.Cpu -> "none"
            else -> encoder.deviceName
        }

    fun plan(policy: DevicePolicy, backends: List<Backend>): Plan {
        val kinds =
            when (policy) {
                DevicePolicy.Auto -> autoOrder
                is DevicePolicy.Prefer -> listOf(policy.kind) + (autoOrder - policy.kind)
                is DevicePolicy.Only -> listOf(policy.kind)
            }
        val candidates = mutableListOf<Candidate>()
        val skipped = mutableListOf<LoadReport.Attempt>()
        for (kind in kinds) {
            val ofKind = backends.filter { kindOf(it.name) == kind }
            val usable = ofKind.filter { it.loaded && it.devices.isNotEmpty() }
            if (usable.isEmpty()) {
                // a backend that's present but unusable is worth a line; one the app doesn't ship isn't, except for
                // Only
                val reason =
                    ofKind.firstOrNull()?.let { b ->
                        if (!b.loaded) "${b.name} didn't load: ${b.error}" else "${b.name} has no device"
                    } ?: if (policy is DevicePolicy.Only) "no ${kind.name} backend in this app" else null
                if (reason != null) skipped += LoadReport.Attempt(kind, null, reason)
                continue
            }
            for (b in usable) {
                if (kind == DeviceKind.Cpu) {
                    candidates += Candidate(kind, b.name, "CPU")
                } else {
                    b.devices.forEach { candidates += Candidate(kind, b.name, it) }
                }
            }
        }
        return Plan(candidates, skipped)
    }
}
