package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.demo.data.settings.Knob
import com.suhel.llamabro.demo.data.settings.Knobs
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D60's layers: the chat over the model over the card over llama-server, and what a request carries. */
class SamplingSettingsTest {
    private val qwenSmall = Catalog.byRepo("unsloth/Qwen3.5-0.8B-GGUF")!!.sampling

    @Test fun theMostSpecificLayerWinsForEachValue() {
        val layers =
            SamplingLayers(
                card = qwenSmall,
                model = Knobs.of(Knob.Temperature to 0.5f, Knob.TopK to 30f),
                chat = Knobs.of(Knob.Temperature to 0.2f),
            )
        val v = layers.resolve(thinks = true)
        assertEquals(KnobValue(0.2f, KnobSource.Chat), v[Knob.Temperature])
        assertEquals(KnobValue(30f, KnobSource.Model), v[Knob.TopK])
        assertEquals(KnobValue(1.5f, KnobSource.Card), v[Knob.PresencePenalty])
    }

    @Test fun theCardsValuesFollowTheMode() {
        val layers = SamplingLayers(qwenSmall, Knobs.None)
        assertEquals(0.95f, layers.resolve(thinks = true).getValue(Knob.TopP).value)
        assertEquals(1f, layers.resolve(thinks = false).getValue(Knob.TopP).value)
        assertEquals(2f, layers.resolve(thinks = false).getValue(Knob.PresencePenalty).value)
    }

    @Test fun aValueNobodySetIsLlamaServersAndIsNotSent() {
        val layers = SamplingLayers(card = null, model = Knobs.of(Knob.MinP to 0.1f))
        val v = layers.resolve(thinks = false)
        assertEquals(KnobValue(0.8f, KnobSource.Server), v[Knob.Temperature])
        val sampling = layers.sampling(thinks = false, maxTokens = 2_048)
        // left out, so a model file's own general.sampling.* value still applies (F17)
        assertNull(sampling.temperature)
        assertEquals(0.1f, sampling.minP!!, 1e-6f)
        assertEquals(2_048, sampling.maxTokens)
    }

    @Test fun aRequestCarriesTheCardsValuesAndTheLengthLimit() {
        val s = SamplingLayers(qwenSmall, Knobs.None).sampling(thinks = true, maxTokens = 2_560)
        assertEquals(1f, s.temperature!!, 1e-6f)
        assertEquals(20, s.topK)
        assertEquals(1.5f, s.presencePenalty!!, 1e-6f)
        assertEquals(2_560, s.maxTokens)
    }

    @Test fun everyCatalogModelHasCardValues() {
        for (entry in Catalog.suggested) {
            assertTrue(entry.realName, !entry.sampling.thinking.isEmpty)
        }
    }

    @Test fun valuesSnapToTheirStepAndRange() {
        assertEquals(0.75f, Knob.Temperature.snap(0.76f), 1e-6f)
        assertEquals(2f, Knob.Temperature.snap(9f), 1e-6f)
        assertEquals(37f, Knob.TopK.snap(36.6f), 1e-6f)
        assertEquals("0.7", Knob.Temperature.format(Knob.Temperature.snap(0.7f)))
        assertEquals("20", Knob.TopK.format(20f))
        assertEquals("0", Knob.MinP.format(0f))
    }

    @Test fun storedValuesSurviveAndBadOnesAreSkipped() {
        val knobs = Knobs.of(Knob.Temperature to 0.7f, Knob.TopK to 20f)
        assertEquals(knobs, Knobs.decode(knobs.encode()))
        assertEquals(Knobs.of(Knob.TopP to 0.9f), Knobs.decode("top_p=0.9;nonsense;top_k=abc;mirostat=2"))
        assertEquals(Knobs.None, Knobs.decode(null))
    }

    @Test fun theReplyLimitIsTheAnswersShareAndTheThinkingBudget() {
        assertEquals(2_048, Thinking.replyTokens(null))
        assertEquals(2_048, Thinking.replyTokens(Think.Off))
        assertEquals(2_560, Thinking.replyTokens(Think.Quick))
        assertEquals(4_096, Thinking.replyTokens(Think.Balanced))
        assertEquals(8_192, Thinking.replyTokens(Think.Deep))
    }

    @Test fun anotherSizeOfACatalogModelUsesItsCard() {
        val other = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q8_0")
        assertEquals(qwenSmall, Catalog.byRepo(other.repo)?.sampling)
    }
}
