package com.suhel.llamabro.demo.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.demo.data.ModelSenses
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.demo.data.settings.LoadConfig
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.testing.FakeLlamaBroHub
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a chat shows of a model: the loaded model's senses, else what its header or last load said, else the catalog's
 * (D76, D100, D155).
 */
class ModelSensesTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun stop() = scope.cancel()

    private val talker = Catalog.suggested.first { it.talks }.key
    private val quiet = Catalog.suggested.first { !it.talks }.key
    private val thinker = Catalog.suggested.first { it.thinks }
    private val search = HfModel.parse("someone/Thinker-GGUF:Q4_K_M")

    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") })
    }

    /** A loaded fake model: its template is empty, so it shows no sign of thinking. */
    private fun loaded(): Model = runBlocking { FakeLlamaBro().llamaBro.loadModel(ModelSource.file("/fake.gguf")) }

    @Test
    fun aSuggestedModelThatTalksShowsTheMicAndVoiceModeBeforeItsFirstLoad() {
        val state = readyOrWaking(talker, "Omni", HostState.Idle) as ChatModelState.Ready
        assertTrue(state.hears)
        assertTrue(state.talks)
    }

    @Test
    fun anotherQuantOfTheSameModelIsTheSameModel() {
        val q8 = HfModel.parse("${talker.repo}:Q8_0")
        val state = readyOrWaking(q8, "Omni", HostState.Idle) as ChatModelState.Ready
        assertTrue(state.hears && state.talks)
    }

    @Test
    fun aModelThatDoesntTalkShowsWhatItsLastLoadHeard() {
        val never = readyOrWaking(quiet, "Quiet", HostState.Idle) as ChatModelState.Ready
        assertFalse(never.hears)
        assertFalse(never.talks)
        val heard =
            readyOrWaking(
                quiet,
                "Quiet",
                HostState.Idle,
                senses = mapOf(quiet.toString() to ModelSenses(thinks = false, sees = false, hears = true)),
            ) as ChatModelState.Ready
        assertEquals(true to false, heard.hears to heard.talks)
    }

    @Test
    fun aSearchModelThatThinksShowsThinkBeforeItsFirstLoad() {
        val remembered = ModelSenses(thinks = true, sees = false, hears = false)
        assertTrue(sensesOf(search, HostState.Idle, remembered, emptySet()).thinks)
    }

    @Test
    fun aCatalogModelAtAnotherQuantStillThinks() {
        val otherQuant = HfModel.parse("${thinker.key.repo}:Q8_0")
        assertEquals(thinker.thinks, sensesOf(otherQuant, HostState.Idle, null, emptySet()).thinks)
    }

    @Test
    fun theLoadedModelOverridesTheHeader() {
        val remembered = ModelSenses(thinks = true, sees = false, hears = false)
        val ready = HostState.Ready(search, loaded(), LoadConfig())
        assertFalse(sensesOf(search, ready, remembered, emptySet()).thinks)
    }

    @Test
    fun headerSensesUseTheSameRuleAsALoad() =
        runBlocking {
            FakeLlamaBroHub {
                repo(search.repo) {
                    gguf("Thinker-Q4_K_M.gguf", chatTemplate = "<think>")
                    gguf("mmproj-F16.gguf", metadata = mapOf("clip.has_vision_encoder" to true))
                }
            }.use { fake ->
                fake.hub.download(search).collect()
                val senses = fake.hub.capabilities(search)!!.senses(null)
                assertTrue(senses.thinks)
                assertTrue(senses.sees)
                assertFalse(senses.hears)
            }
        }

    @Test
    fun aFailedHeaderReadStoresNothing() =
        runBlocking {
            val reader = SensesReader(settings) { throw IllegalArgumentException("a corrupt header") }
            reader.ensure(search)
            assertEquals(emptyMap<String, ModelSenses>(), settings.modelSenses.first())
        }

    @Test
    fun aHeaderThatCantBeReadStoresNothingAndDoesntThrow() =
        runBlocking {
            val reader = SensesReader(settings) { throw IOException("the file was deleted mid-read") }
            reader.ensure(search)
            assertEquals(emptyMap<String, ModelSenses>(), settings.modelSenses.first())
        }

    @Test
    fun aReadCancelledMidwayIsReadAgain() =
        runBlocking {
            val reads = AtomicInteger()
            val started = CompletableDeferred<Unit>()
            val reader =
                SensesReader(settings) {
                    if (reads.incrementAndGet() == 1) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    ModelSenses(thinks = true, sees = false, hears = false)
                }
            val first = launch { reader.ensure(search) }
            started.await()
            first.cancelAndJoin()
            assertEquals(emptyMap<String, ModelSenses>(), settings.modelSenses.first())
            reader.ensure(search)
            assertEquals(2, reads.get())
            assertTrue(
                settings.modelSenses
                    .first()
                    .getValue(search.toString())
                    .thinks,
            )
        }

    @Test
    fun ensureReadsOnce() =
        runBlocking {
            val reads = AtomicInteger()
            val reader =
                SensesReader(settings) {
                    reads.incrementAndGet()
                    ModelSenses(thinks = true, sees = false, hears = false)
                }
            reader.ensure(search)
            reader.ensure(search)
            assertEquals(1, reads.get())
            assertEquals(
                ModelSenses(thinks = true, sees = false, hears = false),
                settings.modelSenses.first()[search.toString()],
            )
        }
}
