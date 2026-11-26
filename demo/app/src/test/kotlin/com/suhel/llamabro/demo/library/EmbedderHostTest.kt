package com.suhel.llamabro.demo.library

import com.suhel.llamabro.DeviceKind
import com.suhel.llamabro.DevicePolicy
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.demo.eventually
import com.suhel.llamabro.demo.letAWrongActionLand
import com.suhel.llamabro.embed
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The embedder's own host: loaded for embeddings beside the chat model under Settings › Processor's chip, checked for
 * fit before it loads, and let go after a while unused.
 */
class EmbedderHostTest {
    private val bge = HfModel.parse("ggml-org/bge-small-en-v1.5-Q8_0-GGUF:Q8_0")
    private val fake = FakeLlamaBro()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var policy: DevicePolicy = DevicePolicy.Auto
    private var estimate = EmbedderEstimate(fits = true, needBytes = 80_000_000, budgetBytes = 2_000_000_000)
    private var beside: ChatBeside? = ChatBeside("Gemma 3 4B", reserveBytes = 0)
    private val reserved = mutableListOf<Long>()
    private val sources = mutableListOf<HfModel>()

    private fun host(idleMillis: Long = 60_000) =
        EmbedderHost(
            fake.llamaBro,
            source = {
                sources += it
                ModelSource.file("/fake/${it.repo}.gguf")
            },
            policy = { policy },
            estimate = { _, _, reserve ->
                reserved += reserve
                estimate
            },
            beside = { beside },
            scope = scope,
            idleMillis = idleMillis,
        )

    @After fun stop() = scope.cancel()

    @Test fun embedderLoadsBesideChatModel() =
        runBlocking {
            val chat = fake.llamaBro.loadModel(ModelSource.file("/fake/chat.gguf"))
            val host = host()
            fake.embeddings(floatArrayOf(1f, 0f))
            // embed() refuses a model loaded for another task: this one was loaded with ModelTask.Embedding
            val vector = host.use(bge) { it.embed("hello") }

            assertEquals(listOf(1f, 0f), vector.toList())
            assertEquals("the chat model stays loaded beside it", 2, fake.openModels)
            assertEquals(listOf(bge), sources)
            assertEquals(bge, host.loaded.value)
            chat.close()
            host.release()
        }

    @Test fun aSecondUseSharesTheLoadAndAnotherChipReloads() =
        runBlocking {
            val host = host()
            val first = host.use(bge) { it }
            assertSame(first, host.use(bge) { it })
            assertEquals(1, sources.size)

            policy = DevicePolicy.Only(DeviceKind.Cpu)
            val second = host.use(bge) { it }
            assertNotSame(first, second)
            assertEquals("the old load is closed before the new one", 1, fake.openModels)
            host.release()
        }

    @Test fun embedderReleasedWhenIdle() =
        runBlocking {
            val host = host(idleMillis = 100)
            host.use(bge) { model: Model -> model }
            assertEquals(1, fake.openModels)
            eventually("the idle embedder is closed") { fake.openModels == 0 }
            assertEquals(null, host.loaded.value)
        }

    @Test fun aUseInProgressIsNeverReleased() =
        runBlocking {
            val host = host(idleMillis = 50)
            host.use(bge) {
                letAWrongActionLand()
                assertEquals(1, fake.openModels)
            }
            // a use that came back within the wait keeps it: the timer starts again after it
            host.use(bge) { }
            eventually("released after the last use") { fake.openModels == 0 }
        }

    @Test fun doesNotFitSaysSoAndLoadsNothing() =
        runBlocking {
            estimate = EmbedderEstimate(fits = false, needBytes = 600_000_000, budgetBytes = 250_000_000)
            beside = ChatBeside("Gemma 3 4B", reserveBytes = 3_341_145_472)
            val host = host()

            val fit = host.fit(bge)
            assertEquals(EmbedderFit.DoesNotFit("Gemma 3 4B", 600_000_000, 250_000_000), fit)
            assertEquals(listOf(3_341_145_472L), reserved)
            try {
                host.use(bge) { }
                fail("an embedder that doesn't fit loaded")
            } catch (e: EmbedderDoesNotFit) {
                assertEquals(fit, e.fit)
            }
            assertEquals(0, fake.openModels)
            assertTrue(sources.isEmpty())
        }

    @Test fun anUnknownFitDoesNotStopTheLoad() =
        runBlocking {
            estimate = EmbedderEstimate(fits = null, needBytes = 80_000_000, budgetBytes = null)
            val host = host()
            assertEquals(EmbedderFit.Fits, host.fit(bge))
            host.use(bge) { }
            assertEquals(1, fake.openModels)
            host.release()
        }
}
