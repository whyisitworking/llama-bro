package com.suhel.llamabro.internal

import com.suhel.llamabro.DeviceKind

/**
 * Why speculative decoding isn't on by default ([com.suhel.llamabro.Speculative.Auto]) for a chip, and where the app's
 * own choice can't run. A chip gets MTP by default only if it is measured faster on both prefill and decode for a 2K
 * prompt with a 256-token reply; plain decoding (upstream's default) wins ties (P2 T8, F67). At b12052 that is no chip,
 * so Auto sends no speculative setting at all.
 */
internal object MtpPolicy {
    fun reason(kind: DeviceKind): String =
        when (kind) {
            DeviceKind.Npu -> {
                "MTP measured slower for prose on the NPU; for JSON or tool calls set " +
                    "Speculative.Mtp(4), about 1.5x decode (F67, F68)"
            }

            DeviceKind.Gpu -> {
                "speculative decoding (MTP, n-gram, DFlash) aborts ggml-opencl at b12352 (F67, F80, F125)"
            }

            DeviceKind.Cpu -> {
                "MTP measured slower or no faster on the CPU (F67, F68)"
            }
        }

    /** Drafted batches abort ggml-opencl on the first drafted batch (F67, F80). */
    const val GPU_ABORTS: String =
        "speculative decoding (MTP, n-gram, DFlash) aborts ggml-opencl at b12352 (F67, F80, F125)"
}
