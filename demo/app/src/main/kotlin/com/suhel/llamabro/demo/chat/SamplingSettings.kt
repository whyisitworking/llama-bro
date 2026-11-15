package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.chat.Sampling
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The sampling values a person can change (D60): the five a model card recommends. Each has the range and step of its
 * slider, and llama-server's own default, which applies when nothing else sets it.
 */
enum class Knob(
    val label: String,
    /** The key in the stored form; llama-server's own name. */
    val key: String,
    val min: Float,
    val max: Float,
    val step: Float,
    val serverDefault: Float,
) {
    Temperature("Temperature", "temperature", 0f, 2f, 0.05f, 0.8f),
    TopP("Top P", "top_p", 0f, 1f, 0.05f, 0.95f),
    TopK("Top K", "top_k", 1f, 100f, 1f, 40f),
    MinP("Min P", "min_p", 0f, 1f, 0.01f, 0.05f),
    PresencePenalty("Presence penalty", "presence_penalty", 0f, 2f, 0.1f, 0f),
    ;

    private companion object {
        const val HUNDREDTHS = 100f
    }

    /** How the value reads: whole numbers for Top K, two decimals at most for the rest. */
    fun format(value: Float): String =
        if (this == TopK) {
            value.roundToInt().toString()
        } else {
            String
                .format(Locale.ROOT, "%.2f", value)
                .trimEnd('0')
                .trimEnd('.')
                .ifEmpty { "0" }
        }

    /**
     * [value] on this knob's step and range, so a slider and the stored value always agree. Rounded to two decimals
     * after the step, so 0.9 is sent as 0.9 and not as the 0.90000004 that float steps add up to.
     */
    fun snap(value: Float): Float {
        val steps = ((value - min) / step).roundToInt()
        val onStep = (min + steps * step).coerceIn(min, max)
        return (onStep * HUNDREDTHS).roundToInt() / HUNDREDTHS
    }
}

/** Some knobs' values: a model card's, or a person's changes for a model or a chat. Unset knobs are not in [values]. */
data class Knobs(val values: Map<Knob, Float> = emptyMap()) {
    operator fun get(knob: Knob): Float? = values[knob]

    fun with(knob: Knob, value: Float?): Knobs =
        Knobs(if (value == null) values - knob else values + (knob to knob.snap(value)))

    val isEmpty: Boolean get() = values.isEmpty()

    /** The stored form, `temperature=0.7;top_k=20`, stable across app versions: knobs by llama-server's names. */
    fun encode(): String = values.entries.joinToString(";") { (k, v) -> "${k.key}=$v" }

    companion object {
        val None = Knobs()

        fun of(vararg pairs: Pair<Knob, Float>) = Knobs(pairs.toMap())

        /** Reads [encode]'s form; unknown keys and bad numbers are skipped, so an old or damaged value never fails. */
        fun decode(text: String?): Knobs =
            Knobs(
                text
                    .orEmpty()
                    .split(';')
                    .mapNotNull { part ->
                        val (key, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                        val knob = Knob.entries.firstOrNull { it.key == key } ?: return@mapNotNull null
                        value.toFloatOrNull()?.let { knob to knob.snap(it) }
                    }.toMap(),
            )
    }
}

/**
 * A model card's recommended values, which can differ with thinking on and off (Qwen3.5's do). Card values the card
 * doesn't state stay unset, and llama-server's defaults apply to them.
 */
data class CardSampling(val thinking: Knobs, val answering: Knobs = thinking) {
    fun forMode(thinks: Boolean): Knobs = if (thinks) thinking else answering
}

/** Where a value in use comes from, most specific first. */
enum class KnobSource { Chat, Model, Card, Server }

data class KnobValue(val value: Float, val source: KnobSource)

/**
 * The values a turn uses (D60): the chat's changes over the model's, over the model card's for the turn's mode, over
 * llama-server's defaults. A value from llama-server is not sent, so a model file's own `general.sampling.*` value
 * still applies (F17).
 */
data class SamplingLayers(val card: CardSampling?, val model: Knobs, val chat: Knobs = Knobs.None) {
    fun resolve(thinks: Boolean): Map<Knob, KnobValue> =
        Knob.entries.associateWith { knob ->
            chat[knob]?.let { KnobValue(it, KnobSource.Chat) }
                ?: model[knob]?.let { KnobValue(it, KnobSource.Model) }
                ?: card?.forMode(thinks)?.get(knob)?.let { KnobValue(it, KnobSource.Card) }
                ?: KnobValue(knob.serverDefault, KnobSource.Server)
        }

    /**
     * The request's sampling: every value that isn't llama-server's default, and the reply's length limit ([maxTokens];
     * null sends none).
     */
    fun sampling(thinks: Boolean, maxTokens: Int?): Sampling {
        val values = resolve(thinks).filterValues { it.source != KnobSource.Server }.mapValues { it.value.value }
        return Sampling {
            values[Knob.Temperature]?.let { temperature = it }
            values[Knob.TopP]?.let { topP = it }
            values[Knob.TopK]?.let { topK = it.roundToInt() }
            values[Knob.MinP]?.let { minP = it }
            values[Knob.PresencePenalty]?.let { presencePenalty = it }
            maxTokens?.let { this.maxTokens = it }
        }
    }
}
