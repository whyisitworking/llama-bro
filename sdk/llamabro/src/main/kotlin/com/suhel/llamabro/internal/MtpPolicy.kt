package com.suhel.llamabro.internal

import com.suhel.llamabro.DeviceKind

/**
 * Why speculative decoding isn't on by default ([com.suhel.llamabro.Speculative.Off]) for a chip, and where the app's
 * own choice can't run. A chip gets MTP by default only if it is measured faster on both prefill and decode for a 2K
 * prompt with a 256-token reply; plain decoding (upstream's default) wins ties (F67). That is no chip today, so the
 * default sends no speculative setting at all. The reasons reach apps through `LoadReport`, so they cite no finding
 * (F67, F68, F80, F125 are the measurements).
 */
internal object MtpPolicy {
    fun reason(kind: DeviceKind): String =
        when (kind) {
            DeviceKind.Npu -> {
                "MTP measured slower for prose on the NPU; for JSON or tool calls set " +
                    "Speculative.Mtp(4), about 1.5x decode"
            }

            DeviceKind.Gpu -> {
                "speculative decoding (MTP, n-gram, DFlash) aborts the OpenCL GPU backend"
            }

            DeviceKind.Cpu -> {
                "MTP measured slower or no faster on the CPU"
            }
        }

    /** Drafted batches abort ggml-opencl on the first drafted batch (F67, F80). */
    const val GPU_ABORTS: String =
        "speculative decoding (MTP, n-gram, DFlash) aborts the OpenCL GPU backend"
}
