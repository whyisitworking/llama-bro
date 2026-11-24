package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.demo.data.settings.CardSampling
import com.suhel.llamabro.demo.data.settings.Knob
import com.suhel.llamabro.demo.data.settings.Knobs
import kotlin.math.roundToInt

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
