package com.suhel.llamabro.hub

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.ModelConfig
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.hub.internal.AndroidStorage
import com.suhel.llamabro.hub.internal.HttpTransport
import com.suhel.llamabro.hub.internal.repoFolder
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Checks the Hub cache on a phone using the loopback fake server started by check.sh. */
@RunWith(AndroidJUnit4::class)
class HubDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val cacheDir get() = File(context.noBackupFilesDir, "llamabro/hub-test")

    @Test fun symlinksAndSpaceInNoBackup() {
        val cache = cacheDir
        cache.deleteRecursively()
        try {
            val blob =
                File(cache, "models--test--flat/blobs/blob").apply {
                    parentFile!!.mkdirs()
                    writeText("hi")
                }
            val snapshot = File(cache, "models--test--flat/snapshots/${"a".repeat(40)}/model.gguf")
            snapshot.parentFile!!.mkdirs()
            val relative = snapshot.parentFile!!.toPath().relativize(blob.toPath())
            Files.createSymbolicLink(snapshot.toPath(), relative)
            assertEquals("hi", snapshot.readText())
            assertEquals(relative, Files.readSymbolicLink(snapshot.toPath()))

            val storage = AndroidStorage(context)
            assertTrue(storage.allocatableBytes(cache) > 0L)
            storage.allocate(cache, 1L shl 20)
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test fun downloadFromFakeHub() =
        runBlocking {
            val cache = cacheDir
            cache.deleteRecursively()
            try {
                cache.mkdirs()
                val config =
                    HubConfig {
                        endpoint = "http://127.0.0.1:18190/"
                        cacheDir = cache
                    }
                val hub =
                    LlamaBroHub(config, cache, HttpTransport(config.endpoint, null, null), AndroidStorage(context))
                val model = HfModel("test/flat:Q8_0")
                hub.download(model).collect()
                val plan = hub.plan(model)
                assertEquals(setOf(HubFile.Role.Model, HubFile.Role.Mmproj), plan.files.map { it.role }.toSet())
                for (part in plan.files) {
                    val repoDir = File(cache, repoFolder(part.sourceRepo))
                    val snapshot = File(repoDir, "snapshots/${part.sourceCommit}/${part.path}")
                    assertTrue("missing snapshot ${part.path}", snapshot.isFile)
                    assertEquals(part.sizeBytes, snapshot.length())
                    val digest = MessageDigest.getInstance("SHA-256")
                    snapshot.inputStream().use { input ->
                        val buffer = ByteArray(1 shl 20)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    assertEquals("SHA-256 of ${part.path}", part.oid, actual)
                }
                val source = hub.source(model).toString()
                for (part in plan.files) {
                    val snapshot =
                        File(cache, "${repoFolder(part.sourceRepo)}/snapshots/${part.sourceCommit}/${part.path}")
                    assertTrue("source omits ${part.path}", source.contains(snapshot.absolutePath))
                }
            } finally {
                cache.deleteRecursively()
            }
        }

    @Test fun estimateBeforeDownloadMatchesLoad() =
        runBlocking {
            val cache = cacheDir
            cache.deleteRecursively()
            try {
                cache.mkdirs()
                val config =
                    HubConfig {
                        endpoint = "http://127.0.0.1:18190/"
                        cacheDir = cache
                    }
                val hub =
                    LlamaBroHub(config, cache, HttpTransport(config.endpoint, null, null), AndroidStorage(context))
                val plan = hub.plan(HfModel("test/qwen:Q4_K_M"))
                val files = InstrumentationRegistry.getInstrumentation().context.getExternalFilesDir(null)!!
                val modelFile = File(files, "Qwen3.5-4B-Q4_K_M.gguf")
                val mmprojFile = File(files, "mmproj-F16.gguf")
                assertTrue("model was not staged: $modelFile", modelFile.isFile)
                assertTrue("projector was not staged: $mmprojFile", mmprojFile.isFile)
                assertEquals(setOf(HubFile.Role.Model, HubFile.Role.Mmproj), plan.files.map { it.role }.toSet())

                val llama = LlamaBro.create(context)
                val fitConfig: ModelConfig.() -> Unit = {
                    contextSize = ContextSize.Auto
                    ramCacheMib = 0
                }
                val start = SystemClock.elapsedRealtime()
                val hubEstimate = llama.estimateFit(hub.model(plan.model), fitConfig)
                val headerMillis = SystemClock.elapsedRealtime() - start
                val realSource = ModelSource.file(modelFile.absolutePath) { mmproj = mmprojFile.absolutePath }
                val realEstimate = llama.estimateFit(realSource, fitConfig)
                Log.i(
                    "LlamaBroHubTest",
                    "T7 estimates headerCtx=${hubEstimate.contextTokens} " +
                        "headerBudget=${hubEstimate.budgetBytes} realCtx=${realEstimate.contextTokens} " +
                        "realBudget=${realEstimate.budgetBytes} bytesAtMinimum=${hubEstimate.bytesAtMinimum}",
                )
                assertEquals(
                    "header and full GGUF projections; budgets ${hubEstimate.budgetBytes} / " +
                        "${realEstimate.budgetBytes}",
                    realEstimate.contextTokens,
                    hubEstimate.contextTokens,
                )
                assertEquals("projected memory at minimum", realEstimate.bytesAtMinimum, hubEstimate.bytesAtMinimum)
                assertTrue("Qwen does not fit the available memory", hubEstimate.fits == true)

                val loaded = llama.loadModel(realSource, fitConfig)
                val loadedContext =
                    try {
                        loaded.info.contextSize
                    } finally {
                        loaded.closeAndJoin()
                    }
                assertTrue(
                    "load context $loadedContext vs estimated ${hubEstimate.contextTokens}",
                    kotlin.math.abs(loadedContext - requireNotNull(hubEstimate.contextTokens)) <= 512,
                )
                Log.i(
                    "LlamaBroHubTest",
                    "T7 headerMs=$headerMillis hubCtx=${hubEstimate.contextTokens} " +
                        "realCtx=${realEstimate.contextTokens} loadedCtx=$loadedContext " +
                        "bytesAtMinimum=${hubEstimate.bytesAtMinimum}",
                )
            } finally {
                cache.deleteRecursively()
            }
            Unit
        }
}
