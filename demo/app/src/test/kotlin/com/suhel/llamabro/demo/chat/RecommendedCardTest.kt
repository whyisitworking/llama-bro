package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.demo.data.ChatRepository
import com.suhel.llamabro.demo.data.ImageStore
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.testing.FakeLlamaBroHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** A model the catalog lacks starts from the hub's recommended sampling (D60, D79): the file's, else the base model's. */
class RecommendedCardTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun stop() = scope.cancel()

    private var now = 0L

    private fun store(recommended: suspend (HfModel) -> CardSampling?): SamplingStore {
        val settings =
            SettingsRepository(
                PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") },
            )
        val chats = ChatRepository(FakeChatDao(), ImageStore(File(folder.root, "images")), System::currentTimeMillis)
        return SamplingStore(settings, chats, recommended, scope) { now }
    }

    @Test fun theFilesValuesWinOverTheBaseModels() {
        val card =
            recommendedCard(
                fromFile = Sampling { temperature = 0f },
                fromBaseModel =
                    Sampling {
                        temperature = 0.6f
                        topK = 20
                    },
            )
        // what loads by default is what the person sees; the base model's values don't overwrite the file's choice
        assertEquals(Knobs.of(Knob.Temperature to 0f), card?.thinking)
        assertEquals(card?.thinking, card?.answering)
    }

    @Test fun theBaseModelsValuesServeWhenTheFileHasNone() {
        val card =
            recommendedCard(
                fromFile = null,
                fromBaseModel =
                    Sampling {
                        temperature = 0.6f
                        topP = 0.95f
                        topK = 20
                        repeatPenalty = 1.1f // not a knob a person can change (D60): left out
                    },
            )
        assertEquals(Knobs.of(Knob.Temperature to 0.6f, Knob.TopP to 0.95f, Knob.TopK to 20f), card?.thinking)
    }

    @Test fun neitherSourceIsNoCard() {
        assertNull(recommendedCard(null, null))
        assertNull(recommendedCard(Sampling { repeatPenalty = 1.1f }, null))
    }

    @Test fun theHubsRecommendationReachesTheLayersFromTheFakeHub() =
        runBlocking {
            FakeLlamaBroHub {
                repo("someone/Thing-GGUF") {
                    baseModel = "someone/Thing"
                    gguf("Thing-Q4_K_M.gguf")
                }
                repo("someone/Thing") { generationConfig("""{"temperature":0.7,"top_p":0.8}""") }
            }.use { fake ->
                val model = HfModel.parse("someone/Thing-GGUF:Q4_K_M")
                // the card arrives when the hub answers, in the background
                val layers =
                    withTimeout(5_000) { store { fake.hub.recommendedCard(it) }.model(model).first { it.card != null } }
                assertEquals(Knobs.of(Knob.Temperature to 0.7f, Knob.TopP to 0.8f), layers.card?.thinking)
                assertEquals(KnobSource.Card, layers.resolve(thinks = false).getValue(Knob.Temperature).source)
            }
        }

    private val other = HfModel.parse("someone/Thing-GGUF:Q4_K_M")
    private val thing = CardSampling(Knobs.of(Knob.Temperature to 0.7f))

    @Test fun aCatalogModelKeepsItsCardAndTheHubIsAskedOncePerModel() =
        runBlocking {
            val asked = AtomicInteger()
            val store =
                store {
                    asked.incrementAndGet()
                    null
                }
            val swift = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M")
            assertEquals(Catalog.byKey(swift)?.sampling, store.card(swift))
            assertEquals(0, asked.get())
            assertNull(store.card(other))
            withTimeout(5_000) { while (asked.get() == 0) delay(10) }
            delay(100)
            assertNull(store.card(other))
            assertEquals("an answer, even none, is kept", 1, asked.get())
        }

    @Test fun aTurnDoesNotWaitForTheLookup() =
        runBlocking {
            // Wi-Fi with no route: the lookup hangs
            val asked = AtomicInteger()
            val store =
                store {
                    asked.incrementAndGet()
                    awaitCancellation()
                }
            val started = System.nanoTime()
            assertNull("nothing is sent: the file's own values apply", store.card(other))
            assertNull(store.card(other))
            val tookMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue("the turn didn't wait: $tookMillis ms", tookMillis < 500)
            withTimeout(5_000) { while (asked.get() == 0) delay(10) }
            assertEquals("one lookup under way, not one per turn", 1, asked.get())
        }

    @Test fun aFailedLookupIsNotRetriedOnEveryTurnButLater() =
        runBlocking {
            val asked = AtomicInteger()
            val store =
                store {
                    asked.incrementAndGet()
                    throw IOException("no route")
                }
            store.card(other)
            withTimeout(5_000) { while (asked.get() == 0) delay(10) }
            delay(100)
            repeat(3) { store.card(other) }
            delay(100)
            assertEquals("backed off", 1, asked.get())
            now += 5 * 60_000L
            store.card(other)
            withTimeout(5_000) { while (asked.get() < 2) delay(10) }
        }

    @Test fun theAnswerServesTheTurnsAfterIt() =
        runBlocking {
            val store = store { thing }
            assertNull("the first turn goes without", store.card(other))
            withTimeout(5_000) { while (store.card(other) == null) delay(10) }
            assertEquals(thing, store.card(other))
        }
}
