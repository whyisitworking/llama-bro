package com.suhel.llamabro.internal

import com.suhel.llamabro.Backend
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.FitEstimate
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.ModelConfig
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.spi.Engine
import com.suhel.llamabro.spi.NativeError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Projects the first device a load would attempt, without opening model weights: the [LoadPlanner]'s first usable
 * candidate, with the same memory budget the load would use. It owns the projection and its mapping to a
 * [FitEstimate]; choosing the device is the planner's, so the estimate names the device the load would use.
 * Blocking: call it on the IO dispatcher.
 */
internal class FitEstimator(
    private val engine: Engine,
    private val slotsDir: File,
    private val mediaDir: File,
    private val budgetFor: (ModelSource, ModelConfig) -> Long?,
) {
    fun estimate(source: ModelSource, builder: ModelConfig, backends: List<Backend>): FitEstimate {
        val plan = LoadPlanner.plan(source, builder, backends)
        val candidate =
            plan.steps.firstOrNull { it.skip == null }?.candidate
                ?: run {
                    val kind = (builder.devicePolicy as? DevicePolicy.Only)?.kind ?: DeviceKind.Cpu
                    val reasons = plan.skipped.map { it.reason } + plan.steps.mapNotNull { it.skip?.reason }
                    throw LlamaBroException.DeviceUnavailable(
                        kind,
                        "no ${kind.name} device could estimate the model: ${reasons.joinToString("; ")}",
                        null,
                    )
                }
        slotsDir.mkdirs()
        val configJson = builder.loadJson(slotsDir, mediaDir, source, candidate.devices, budgetFor(source, builder))
        val result =
            try {
                Json.parseToJsonElement(engine.fitEstimate(configJson)) as JsonObject
            } catch (error: NativeError) {
                throw ErrorMapping.loadFailed(error)
            }

        fun nullableLong(key: String): Long? = result[key]?.jsonPrimitive?.longOrNull
        return FitEstimate(
            fits = result["fits"]?.jsonPrimitive?.booleanOrNull,
            contextTokens = result["n_ctx"]?.jsonPrimitive?.intOrNull,
            minContextTokens = result.getValue("n_ctx_min").jsonPrimitive.int,
            bytesAtMinimum = result.getValue("used_min_mib").jsonPrimitive.long * BYTES_PER_MIB,
            bytesAtCeiling = nullableLong("used_max_mib")?.times(BYTES_PER_MIB),
            budgetBytes = nullableLong("budget_mib")?.times(BYTES_PER_MIB),
            device = candidate.kind,
            deviceName = candidate.deviceName,
        )
    }
}
