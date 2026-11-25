package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.loadConfig
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Records whether the model heard on this load, so the mic shows before its next load (D76). */
private suspend fun rememberHearing(settings: SettingsRepository, key: HfModel, hears: Boolean) {
    val known = key.toString() in settings.hearingModels.first()
    if (known != hears) settings.setHears(key.toString(), hears)
}

/**
 * Records what the model heard and spoke with on this load (D76, D100): the mic and the voice-mode button show before
 * its next load, and Settings › Voice lists its voices.
 */
internal suspend fun rememberSenses(settings: SettingsRepository, key: HfModel, info: ModelInfo) {
    rememberHearing(settings, key, info.supportsAudio)
    val voices = if (info.supportsAudio) info.voices else emptyList()
    if (settings.talkingModels.first()[key.toString()].orEmpty() != voices) settings.setVoices(key.toString(), voices)
}

/**
 * An audio feature's hold on the loaded [model] (voice mode D100, dictation D140, reading aloud D141): [release] it
 * once, when the feature is done with it. Features acquire it through [holdUncancelled].
 */
class ModelHold(val model: Model, private val onRelease: () -> Unit) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}

/**
 * [hold] run to its end even when the caller is cancelled meanwhile (a model load can't be stopped halfway by it): a
 * hold that arrives for a cancelled caller is released rather than dropped, and the caller then sees the cancellation.
 */
suspend fun holdUncancelled(hold: suspend () -> ModelHold?): ModelHold? {
    val held = withContext(NonCancellable) { hold() }
    if (!currentCoroutineContext().isActive) held?.release()
    currentCoroutineContext().ensureActive()
    return held
}

/**
 * The engine's leases on the model, for turns and for audio features' holds, and the count of [activeTurns] they make:
 * the model isn't freed while one is under way, and a reply keeps the app alive while it runs (D59).
 */
internal class ModelLeases(
    private val host: ModelHost,
    private val settings: SettingsRepository,
    /** The model server mode serves: a turn on it shares its load, whatever its config. */
    private val served: () -> HfModel? = { null },
) {
    /** Turns under way in every chat, from the load to the end of the reply: the engine's own count, in and out. */
    val activeTurns = MutableStateFlow(0)

    /**
     * The loaded model whose note (memory or chip) was shown: once per load, in whichever chat loads it first. A model
     * loaded again (after it was freed, or after a switch) is a new object, so its note is shown again.
     */
    @Volatile private var noted: java.lang.ref.WeakReference<Model>? = null

    /**
     * A lease on the model for [key] (shown as [name]) on the chip and context chosen in Settings › Memory (D72), or
     * null when it didn't load: [limit] then says why. When the SDK loaded it although its memory check doubted it
     * (F128), or on another chip than the one chosen, [limit] says so on every load of it: the note travels with the
     * loaded model into every turn, so a turn on a model already loaded doesn't say it again. The turn releases the
     * lease when it ends: until then the model stays open, and another chat's load of another model waits for it.
     */
    suspend fun load(key: HfModel, name: String, limit: MutableStateFlow<Limit?>, slots: Int = 1): ModelHost.Lease? {
        // another model while one is served: its load would wait for serving to stop, and every server request would
        // queue behind it, so it fails at once and says why
        served()?.takeIf { it != key }?.let { other ->
            limit.value = Limits.serving(Names.of(other), name)
            return null
        }
        val chosen =
            settings.settings
                .first()
                .loadConfig
                .copy(slots = slots)
        val lease =
            try {
                // this turn is counted: another one may be streaming from the model, so a chip or context changed
                // meanwhile waits for it (ModelMemory.loadConfigChanged) instead of closing the model under that reply
                // a served model is shared too: replacing it would wait for the server's lease until serving stops
                host.hold(key, chosen) { activeTurns.value > 1 || served() == key }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                limit.value = Limits.forLoadFailure(name, e)
                    ?: Limit("$name didn't start. Try again.", LimitAction.Retry, "Retry")
                return null
            }
        var handedOver = false
        try {
            val loaded = lease.model
            // one line holds one note: the memory note wins, since it warns that other apps may close and offers a
            // smaller size; where the model runs stays in Settings › Memory and the model sheet
            val report = loaded.loadReport
            val note =
                if (report.memoryWarning != null) {
                    Limits.memoryWarning(name)
                } else {
                    report.fallbackFrom(lease.config.policy)?.let { Limits.chipFallback(name, it) }
                }
            if (note != null && firstNote(loaded)) limit.value = note
            handedOver = true
            return lease
        } finally {
            // a throw here hands the turn no lease to release, so it must not leave the model held
            if (!handedOver) lease.release()
        }
    }

    @Synchronized
    private fun firstNote(loaded: Model): Boolean {
        if (noted?.get() === loaded) return false
        noted = java.lang.ref.WeakReference(loaded)
        return true
    }

    /** The hold behind [ChatEngine.holdModel]; it counts as a turn until [ModelHold.release]. */
    suspend fun hold(key: HfModel, name: String, slots: Int, limit: MutableStateFlow<Limit?>): ModelHold? {
        activeTurns.update { it + 1 }
        var lease: ModelHost.Lease? = null
        var handedOver = false
        try {
            lease = load(key, name, limit, slots) ?: return null
            rememberSenses(settings, key, lease.model.info)
            handedOver = true
        } finally {
            // no model, or a throw after the load: the caller gets no hold to release, so nothing may stay held
            if (!handedOver) {
                lease?.release()
                activeTurns.update { it - 1 }
            }
        }
        val held = checkNotNull(lease)
        return ModelHold(held.model) {
            held.release()
            activeTurns.update { it - 1 }
        }
    }
}
