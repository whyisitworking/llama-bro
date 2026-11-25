package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.LoadReport
import com.suhel.llamabro.demo.data.ChipChoice
import com.suhel.llamabro.demo.data.ContextChoice

/**
 * A model that runs on another chip than the one chosen in Settings › Processor (D72): where it runs ([runsOn]), the
 * chip chosen ([wanted]) and [why], in plain words ("the GPU couldn't load it").
 */
data class ChipFallback(val runsOn: DeviceKind, val wanted: DeviceKind, val why: String) {
    /** The line under the choice in Settings › Processor. */
    val line: String get() = "Running on the ${runsOn.label}: $why."
}

/** A chip as the person reads it: "NPU", "GPU", "CPU". */
val DeviceKind.label: String get() = name.uppercase()

/**
 * Where this load landed against [policy], or null when it is on the chip asked for. `Auto` asks for no chip in
 * particular, so it never falls back: it is the fastest chip that loaded. The SDK's reasons are written for developers
 * (a backend's error, a native message), so only their gist is said.
 */
fun LoadReport.fallbackFrom(policy: DevicePolicy): ChipFallback? {
    val wanted = (policy as? DevicePolicy.Prefer)?.kind ?: return null
    if (device == wanted) return null
    // the model's own attempt on that chip; a line about the image encoder moving is a detail of it, said only when
    // there is no other
    val ofWanted = attempts.filter { it.kind == wanted }
    val attempt = ofWanted.lastOrNull { !it.reason.startsWith(ENCODER_LINE) } ?: ofWanted.lastOrNull()
    return ChipFallback(device, wanted, fallbackWhy(wanted, attempt != null, attempt?.deviceName))
}

/**
 * Why [wanted] wasn't used: it wasn't [tried] at all (no backend for it in the app), its backend had no device here
 * ([deviceName] null), or a load on it failed. A load that fails for memory doesn't fall back (the SDK stops
 * there), so a failed load is said plainly.
 */
internal fun fallbackWhy(wanted: DeviceKind, tried: Boolean, deviceName: String?): String =
    when {
        !tried -> "this phone has no ${wanted.label}"
        deviceName == null -> "the ${wanted.label} isn't available here"
        else -> "the ${wanted.label} couldn't load it"
    }

/** How the SDK starts the line for an image encoder that moved to follow the model (`LlamaBroRuntime`). */
private const val ENCODER_LINE = "image encoder"

/**
 * The line under Settings › Processor choice (D72) for the model in memory ([ready], null when none): where it runs
 * and why when it was loaded for the chip [chosen] and landed elsewhere, or, while it is still the one loaded for an
 * earlier choice, when it moves; null when there is nothing to say.
 */
fun runsOnLine(ready: HostState.Ready?, chosen: ChipChoice, replying: Boolean): String? {
    ready ?: return null
    val report = ready.model.loadReport
    val policy = ready.config.policy
    if (policy == chosen.policy) return report.fallbackFrom(policy)?.line
    // already on the chip chosen (Auto landed there): nothing moves for the person, though the model still reloads
    // under the new choice, since Prefer and Auto fall back in different orders
    if (chosen.kind == report.device) return null
    val target = chosen.kind?.let { "the ${it.label}" } ?: "the fastest chip"
    return if (replying) "Moves to $target after this reply." else "Moves to $target with the next message."
}

/**
 * The line under Settings › Inference context length for the model in memory ([ready], null when none): while it is
 * still the one loaded for another length than the one [chosen], when it reloads at the new one, as [runsOnLine] says
 * when a chip change moves it; null when there is nothing to say.
 */
fun contextLine(ready: HostState.Ready?, chosen: ContextChoice, replying: Boolean): String? {
    if (ready == null || ready.config.context == chosen.size) return null
    val target = chosen.tokens?.let(::kTokens) ?: "the most that fits"
    return if (replying) "Reloads at $target after this reply." else "Reloads at $target with the next message."
}
