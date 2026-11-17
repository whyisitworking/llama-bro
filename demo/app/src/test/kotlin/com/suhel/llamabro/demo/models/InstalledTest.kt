package com.suhel.llamabro.demo.models

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.suhel.llamabro.demo.data.SettingsRepository
import com.suhel.llamabro.hub.DownloadState
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
 * A model whose first file has arrived but whose image reader hasn't is not on the phone (D64): loading it through
 * `hub.model` would fetch the rest in the foreground, so the app neither lists nor loads it.
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
     * The model file in the cache and the image reader not, as a download stopped between the two leaves them. The
     * hub doesn't list it (D89), and the app mustn't either.
     */
    private fun halfDownloaded() =
        runBlocking {
            fake.stopDownload(model, "mmproj-F16.gguf")
            // half downloaded, not nothing: the model file's bytes are there
            val state = hub.progress(model).value
            assertTrue("$state", state is DownloadState.Partial && state.bytesDone >= MODEL_BYTES)
            assertFalse("the hub's list leaves it out", hub.cached().any { it.model == model })
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
