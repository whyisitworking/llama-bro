package com.suhel.llamabro.demo.data.settings

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
