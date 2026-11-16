package com.suhel.llamabro.demo.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.testing.FakeLlamaBroHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A model whose first file has arrived but whose image reader hasn't is not on the phone (D64): `hub.cached()` lists it,
 * yet loading it through `hub.model` would fetch the rest in the foreground, so the app neither lists nor loads it.
 */
class InstalledTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val model = HfModel.parse("someone/Seer-GGUF:Q4_K_M")
    private val fake = newHub()

    private fun newHub() =
        FakeLlamaBroHub {
            repo(model.repo) {
                gguf("Seer-Q4_K_M.gguf", sizeBytes = MODEL_BYTES)
                gguf("mmproj-F16.gguf", sizeBytes = MODEL_BYTES)
            }
        }

    private val hub get() = fake.hub

    @After fun stop() {
        scope.cancel()
        fake.close()
    }

    /**
     * The model file in the cache and the image reader missing, as a download stopped between the two leaves them. The
     * hub takes a selection's files in no fixed order, so the state is made the same way every time: a complete
     * download whose reader file is then taken out of the snapshot. Its path is read from the installed source (the
     * core's file source names it; the fake hub's folder is private).
     */
    private fun halfDownloaded() =
        runBlocking {
            hub.download(model).collect()
            val reader =
                Regex("mmproj=([^,)]+)").find(hub.source(model).toString())?.groupValues?.get(1)
                    ?: error("the installed source names no image reader: ${hub.source(model)}")
            assertTrue("the reader's file is taken out", File(reader).delete())
            assertTrue("the hub's raw list has it", hub.cached().any { it.model == model })
        }

    @Test fun aModelWithItsImageReaderMissingIsNotOnPhoneAndLoadingItMakesNoRequest() =
        runBlocking {
            halfDownloaded()
            val before = fake.requests.size
            try {
                hub.installedModel(model)
                fail("expected NotOnPhone")
            } catch (expected: NotOnPhone) {
                assertEquals(model, expected.model)
            }
            assertFalse(hub.isInstalled(model))
            assertEquals("no request: nothing was fetched", before, fake.requests.size)
        }

    @Test fun theLibraryLeavesItOutAndGetResumesIt() =
        runBlocking<Unit> {
            halfDownloaded()
            val settings =
                SettingsRepository(
                    PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "s.preferences_pb") },
                )
            val scheduler = FakeScheduler()
            val repo = ModelRepository(hub, settings, scheduler, scope)
            assertEquals(emptyList<LibraryModel>(), repo.library.first())
            repo.get(model)
            scheduler.awaitEnqueued(model)
            withTimeout(5_000) { repo.downloading.first { it == listOf(model) } }
            // once the rest arrives it is listed
            hub.download(model).collect()
            withTimeout(5_000) { repo.library.first { rows -> rows.any { it.key == model } } }
        }

    private companion object {
        const val MODEL_BYTES = 4096
    }
}
