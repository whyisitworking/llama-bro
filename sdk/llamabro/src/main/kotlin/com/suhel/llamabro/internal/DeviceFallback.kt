package com.suhel.llamabro.internal

import com.suhel.llamabro.Backend
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.FileSource
import com.suhel.llamabro.LlamaBroException
import com.suhel.llamabro.LoadReport
import com.suhel.llamabro.MemoryCheck
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelConfig
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.Speculative
import com.suhel.llamabro.spi.Engine
import com.suhel.llamabro.spi.EngineModel
import com.suhel.llamabro.spi.NativeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * One model load on one device, with what a retry on it or on the next device needs. [failures] is the running
 * record of what failed so far (a skipped GPU, a device that refused, an encoder that moved back); it is shared by the
 * attempts of one load, and each [LoadReport] gets a snapshot of it. [encoder] is where the image encoder runs, null
 * without an `mmproj`.
 */
internal class LoadAttempt(
    val source: ModelSource,
    val builder: ModelConfig,
    val candidate: DeviceResolver.Candidate,
    val encoder: DeviceResolver.Candidate?,
    val encoderPolicy: DevicePolicy?,
    val failures: MutableList<LoadReport.Attempt>,
    val onProgress: (Float) -> Unit,
) {
    /** The same load with the encoder on [candidate]; it shares this attempt's failure record. */
    fun withEncoder(candidate: DeviceResolver.Candidate) =
        LoadAttempt(source, builder, this.candidate, candidate, encoderPolicy, failures, onProgress)
}

/**
 * Loads a model on the first device of the [LoadPlanner]'s plan that takes it, and owns what that needs: a failed load
 * frees itself and its reason goes in the report before the next device is tried, an image encoder that was moved
 * elsewhere falls back to the model's device (D53), a refused memory check can retry at the smallest context (D57),
 * and a cancelled caller frees the model that finished loading meanwhile (D67). It is the one place that turns a
 * native load into a [Model], so that this state machine does not share a file with native init and logging.
 */
internal class DeviceFallback(
    private val engine: Engine,
    private val ioDispatcher: CoroutineDispatcher,
    private val slotsDir: File,
    private val mediaDir: File,
    private val mediaIo: MediaIo,
    private val budgetFor: (ModelSource, ModelConfig) -> Long?,
    /** Starts a model's native shutdown ([LlamaBroRuntime.shutdown]), so the next load waits for it. */
    private val shutdown: (() -> Unit) -> Deferred<Unit>,
) {
    /**
     * Tries the plan's devices in order, one model load each: a failed load frees itself, its reason goes in
     * the report, and the next device is tried. A missing file or too little memory stops at once, since no device can
     * fix either.
     */
    suspend fun loadOnFirstDevice(
        source: ModelSource,
        builder: ModelConfig,
        backends: List<Backend>,
        onProgress: (Float) -> Unit,
    ): Model {
        val plan = LoadPlanner.plan(source, builder, backends)
        val attempts = plan.skipped.toMutableList()
        var lastError: LlamaBroException.ModelLoadFailed? = null
        for (step in plan.steps) {
            currentCoroutineContext().ensureActive()
            if (step.skip != null) {
                attempts += step.skip
                continue
            }
            val candidate = step.candidate
            try {
                val encoder =
                    if (source is FileSource && source.mmproj != null) {
                        DeviceResolver.encoder(plan.encoderPolicy, candidate, backends)
                    } else {
                        null
                    }
                return loadWithEncoderFallback(
                    LoadAttempt(source, builder, candidate, encoder, plan.encoderPolicy, attempts, onProgress),
                )
            } catch (e: LlamaBroException.ModelLoadFailed) {
                if (e.isFinal()) throw e
                attempts += LoadReport.Attempt(candidate.kind, candidate.deviceName, e.message ?: "load failed")
                lastError = e
            }
        }
        throw noDeviceLoaded(builder.devicePolicy, attempts, lastError)
    }

    /**
     * One model device: with the encoder moved elsewhere by `Auto` or `Prefer` and the load failing, the same device is
     * tried once more with the encoder following the model, as those policies promise (D53); the failure is recorded.
     * `Only` doesn't fall back.
     */
    private suspend fun loadWithEncoderFallback(attempt: LoadAttempt): Model {
        val encoder = attempt.encoder
        val moved = encoder != null && encoder.deviceName != attempt.candidate.deviceName
        if (!moved || attempt.encoderPolicy is DevicePolicy.Only) return loadModel(attempt)
        return try {
            loadModel(attempt)
        } catch (e: LlamaBroException.ModelLoadFailed) {
            if (e.isFinal()) throw e
            attempt.failures +=
                LoadReport.Attempt(
                    encoder.kind,
                    encoder.deviceName,
                    "image encoder on ${encoder.deviceName} failed (${e.message ?: "load failed"}); " +
                        "it follows the model instead",
                )
            loadModel(attempt.withEncoder(attempt.candidate))
        }
    }

    private suspend fun loadModel(attempt: LoadAttempt): Model {
        // The native load stops only at a progress tick, by the answer it gets there (D67, F145): the caller's job,
        // read here, since inside NonCancellable the context's job is never cancelled. A cancel after the last tick
        // can't stop it, and withContext drops its result if the caller was cancelled meanwhile (prompt cancellation).
        // So the model is recorded as soon as it exists and closed on cancellation, instead of leaking its weights and
        // KV cache.
        val caller = currentCoroutineContext().job
        // a fallback attempt (the encoder following the model) must not start on a cancelled caller's behalf (D67)
        caller.ensureActive()
        var loaded: Model? = null
        try {
            withContext(ioDispatcher + NonCancellable) {
                slotsDir.mkdirs()
                val budget = budgetFor(attempt.source, attempt.builder)
                val configJson =
                    attempt.builder.loadJson(
                        slotsDir,
                        mediaDir,
                        attempt.source,
                        attempt.candidate.devices,
                        budget,
                        attempt.encoder?.let { DeviceResolver.encoderConfig(it, attempt.candidate) },
                    )
                val (engineModel, memoryWarning) =
                    loadOrWarn(attempt, configJson, caller) { p ->
                        attempt.onProgress(p)
                        caller.isActive
                    }
                // the native model exists from here: anything that fails before it is handed out must free it
                loaded =
                    try {
                        handOut(engineModel, memoryWarning, attempt)
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Throwable,
                    ) {
                        // freed, then rethrown typed
                        engineModel.close()
                        throw e as? LlamaBroException
                            ?: LlamaBroException.ModelLoadFailed(
                                LlamaBroException.ModelLoadFailed.Reason.Other,
                                "the loaded model's info could not be read: ${e.message}",
                                e,
                            )
                    }
            }
            currentCoroutineContext().ensureActive()
            return checkNotNull(loaded)
        } catch (e: CancellationException) {
            // freed before the cancellation reaches the caller, as if the load never happened
            loaded?.let { m -> withContext(NonCancellable) { m.closeAndJoin() } }
            throw e
        }
    }

    /**
     * Loads [configJson]. When the memory check refuses it and the app asked to be warned ([MemoryCheck.Warn], D57),
     * loads again at the smallest context without the budget, since the check can't see what Android frees by closing
     * cached apps (F128), and returns the check's message as the warning.
     */
    private fun loadOrWarn(
        attempt: LoadAttempt,
        configJson: String,
        caller: Job,
        onProgress: (Float) -> Boolean,
    ): Pair<EngineModel, String?> {
        val failure =
            try {
                return engine.load(configJson, onProgress) to null
            } catch (e: NativeError) {
                loadFailedOrCancelled(e)
            }
        if (!warnsInstead(attempt.builder, failure)) throw failure
        // the second load would start on a cancelled caller's behalf and stop only at its first tick (D67)
        caller.ensureActive()
        val smallest =
            attempt.builder.loadJson(
                slotsDir,
                mediaDir,
                attempt.source,
                attempt.candidate.devices,
                null,
                attempt.encoder?.let { DeviceResolver.encoderConfig(it, attempt.candidate) },
                smallestContext = true,
            )
        val model =
            try {
                engine.load(smallest, onProgress)
            } catch (e: NativeError) {
                throw loadFailedOrCancelled(e)
            }
        return model to failure.message?.removePrefix("failed to load model: ")
    }

    /**
     * A refused load's exception: a stop the progress answer asked for is the caller's cancellation, thrown after the
     * engine freed what it read (D67), so no device fallback or retry treats it as a failure.
     */
    private fun loadFailedOrCancelled(e: NativeError): LlamaBroException.ModelLoadFailed {
        if (e.status == NativeError.CANCELLED) throw CancellationException("model load cancelled")
        return ErrorMapping.loadFailed(e)
    }

    /** A fitted context that doesn't fit even at its smallest, for an app that asked to be warned (F128). */
    private fun warnsInstead(builder: ModelConfig, failure: LlamaBroException.ModelLoadFailed) =
        failure.reason == LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory &&
            builder.memoryCheck == MemoryCheck.Warn &&
            builder.contextSize !is ContextSize.Fixed

    /** No device fixes a missing file, and every device shares the phone's memory. */
    private fun LlamaBroException.ModelLoadFailed.isFinal() =
        reason == LlamaBroException.ModelLoadFailed.Reason.FileNotFound ||
            reason == LlamaBroException.ModelLoadFailed.Reason.InsufficientMemory

    /** What to throw when no candidate loaded the model. */
    private fun noDeviceLoaded(
        policy: DevicePolicy,
        attempts: List<LoadReport.Attempt>,
        lastError: LlamaBroException.ModelLoadFailed?,
    ): Exception {
        if (policy is DevicePolicy.Only || lastError == null) {
            val kind = (policy as? DevicePolicy.Only)?.kind ?: DeviceKind.Cpu
            return LlamaBroException.DeviceUnavailable(
                kind,
                "no ${kind.name} device could load the model: " +
                    attempts.joinToString("; ") { "${it.deviceName ?: it.kind.name}: ${it.reason}" },
                lastError,
            )
        }
        return lastError // the last device tried's own reason (the CPU's, unless Prefer(Cpu) put it first)
    }

    /** The loaded native model as the app's [Model], with the report of how and where it loaded. */
    private fun handOut(engineModel: EngineModel, memoryWarning: String?, attempt: LoadAttempt): Model {
        val builder = attempt.builder
        val candidate = attempt.candidate
        val encoder = attempt.encoder
        val (contextSize, contextReason) =
            if (memoryWarning != null) {
                contextOf(engineModel.infoJson, builder.contextSize).first to
                    "the smallest context, loaded although the memory check said it may not fit " +
                    "(MemoryCheck.Warn)"
            } else {
                contextOf(engineModel.infoJson, builder.contextSize)
            }
        val (speculative, speculativeReason) = speculativeOf(builder.speculative, candidate.kind, attempt.source)
        val report =
            LoadReport(
                candidate.kind,
                candidate.deviceName,
                candidate.backend,
                attempt.failures.toList(),
                contextSize,
                contextReason,
                speculative,
                speculativeReason,
                encoder?.kind,
                encoder?.deviceName,
                memoryWarning,
            )
        return Model(engineModel, report, shutdown, builder.task, mediaIo)
    }
}

/** What decodes and why: what the app set, or for [Speculative.Off] why it is off on this chip. */
private fun speculativeOf(requested: Speculative, kind: DeviceKind, source: ModelSource): Pair<Speculative, String> {
    val result =
        when (requested) {
            Speculative.Off -> {
                Speculative.Off to "off: ${MtpPolicy.reason(kind)}"
            }

            is Speculative.Mtp -> {
                requested to "MTP: set by the app"
            }

            is Speculative.Ngram -> {
                requested to
                    (if (requested.then != null) "n-gram, then MTP: set by the app" else "n-gram: set by the app")
            }

            is Speculative.DFlash -> {
                requested to "DFlash draft: set by the app"
            }
        }
    val unusedDraft = (source as? FileSource)?.draft != null && requested !is Speculative.DFlash
    return if (unusedDraft) {
        result.first to "${result.second}; draft file unused (Speculative is not DFlash)"
    } else {
        result
    }
}

/** The loaded context and why (`lb_model_info`'s `n_ctx` and `ctx_fit`, D33). */
private fun contextOf(infoJson: String, requested: ContextSize): Pair<Int, String> {
    val info = Json.parseToJsonElement(infoJson) as JsonObject
    val nCtx = info.getValue("n_ctx").jsonPrimitive.int
    val fit = info["ctx_fit"] as? JsonObject
    val reason =
        when {
            requested is ContextSize.Fixed -> {
                "set by the app"
            }

            fit == null -> {
                "min(${(requested as? ContextSize.UpTo)?.maxTokens ?: ContextSize.AUTO_CEILING}, the training context): no memory figure to fit to"
            }

            else -> {
                fun num(key: String) = fit[key]?.jsonPrimitive?.long
                val budget = num("budget_mib")
                val used = num("used_max_mib") ?: num("used_min_mib")
                // n_ctx_cap is per slot (several slots each get it); the other figures are totals
                if (nCtx.toLong() == (num("n_ctx_cap") ?: 0) * (num("n_streams") ?: 1)) {
                    if (num("n_ctx_cap") == num("n_ctx_train")) {
                        "the training context fits: $used MiB of $budget MiB available"
                    } else {
                        "the ceiling of ${num("n_ctx_cap")} tokens fits: $used MiB of $budget MiB available"
                    }
                } else {
                    "fitted to $budget MiB available: ${num("used_min_mib")} MiB at ${num("n_ctx_min")} tokens, " +
                        "${num("used_max_mib")} MiB at ${num("n_ctx_max")}"
                }
            }
        }
    return nCtx to reason
}
