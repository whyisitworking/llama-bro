package com.suhel.llamabro.internal

import com.suhel.llamabro.Backend
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.FileSource
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.LoadReport
import com.suhel.llamabro.ModelConfig
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.Speculative

/** Whether the model decodes with a draft (MTP, n-gram or DFlash), which never runs on the GPU (F67, F80). */
internal fun ModelConfig.drafts() =
    speculative is Speculative.Mtp || speculative is Speculative.Ngram || speculative is Speculative.DFlash

/**
 * Decides which devices a model may be tried on, once, for both [DeviceFallback] (the load) and [FitEstimator] (the
 * estimate), so that the two cannot disagree: the policy's candidates in order, with the rule that drafting skips the
 * GPU and the reason a skipped device is reported with. It runs nothing and reads no model.
 */
internal object LoadPlanner {
    /** A candidate device; [skip] is why it must not be tried (an attempt for the report), or null. */
    class Step(val candidate: DeviceResolver.Candidate, val skip: LoadReport.Attempt?)

    /**
     * [skipped] are devices the policy ruled out before any candidate (a backend that failed to load, say); [steps]
     * are the candidates in order, the unusable ones carrying their [Step.skip]. [encoderPolicy] is the image
     * encoder's, only when the source has an `mmproj`.
     */
    class Plan(val encoderPolicy: DevicePolicy?, val skipped: List<LoadReport.Attempt>, val steps: List<Step>)

    /** Throws [LlamaBroException.DeviceUnavailable] when `encoderDevice = Only(kind)` has no device (D53). */
    fun plan(source: ModelSource, builder: ModelConfig, backends: List<Backend>): Plan {
        val encoderPolicy = builder.encoderDevice.takeIf { (source as? FileSource)?.mmproj != null }
        requireEncoderDevice(encoderPolicy, backends)
        val devices = DeviceResolver.plan(builder.devicePolicy, backends)
        val drafts = builder.drafts()
        val steps =
            devices.candidates.map { candidate ->
                // drafting on the GPU aborts the process, which no try can catch: skip it (F67, F80)
                val skip =
                    if (drafts && candidate.kind == DeviceKind.Gpu) {
                        LoadReport.Attempt(candidate.kind, candidate.deviceName, MtpPolicy.GPU_ABORTS)
                    } else {
                        null
                    }
                Step(candidate, skip)
            }
        return Plan(encoderPolicy, devices.skipped, steps)
    }

    private fun requireEncoderDevice(policy: DevicePolicy?, backends: List<Backend>) {
        if (policy !is DevicePolicy.Only) return
        val plan = DeviceResolver.plan(policy, backends)
        if (plan.candidates.isEmpty()) {
            throw LlamaBroException.DeviceUnavailable(
                policy.kind,
                "no ${policy.kind.name} device for the image encoder: " + plan.skipped.joinToString("; ") { it.reason },
                null,
            )
        }
    }
}
