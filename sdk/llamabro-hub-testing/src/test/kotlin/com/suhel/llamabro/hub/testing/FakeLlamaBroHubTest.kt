package com.suhel.llamabro.hub.testing

import com.suhel.llamabro.DownloadPhase
import com.suhel.llamabro.LoadProgress
import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.hub.DownloadState
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.SearchSort
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The fake hub as an app's test uses it: the real LlamaBroHub over a scripted Hub and disk (D70, D75). */
class FakeLlamaBroHubTest {
    private val qwen = "unsloth/Qwen3-4B-GGUF"
    private val model = HfModel("$qwen:Q4_K_M")

    @Test
    fun scriptedSearchIsReturned() =
        runBlocking {
            val fake =
                FakeLlamaBroHub {
                    repo(qwen) {
                        baseModel = "Qwen/Qwen3-4B"
                        downloads = 900
                        likes = 3
                        gguf("Qwen3-4B-Q4_K_M.gguf")
                    }
                    repo("bartowski/Qwen_Qwen3-4B-GGUF") {
                        baseModel = "Qwen/Qwen3-4B"
                        downloads = 500
                        likes = 9
                        gguf("Qwen_Qwen3-4B-Q8_0.gguf")
                    }
                    repo("ggml-org/gemma-3-4b-it-GGUF") {
                        downloads = 2000
                        takesImages = true
                        gguf("gemma-3-4b-it-Q4_K_M.gguf")
                        gguf("mmproj-model-f16.gguf")
                    }
                    repo("Qwen/Qwen3-4B") { generationConfig("""{"temperature":0.6}""") }
                }

            val qwens = fake.hub.search("qwen3")
            assertEquals(listOf("Qwen/Qwen3-4B"), qwens.results.map { it.id })
            assertEquals(listOf(qwen, "bartowski/Qwen_Qwen3-4B-GGUF"), qwens.results[0].repos.map { it.id })
            assertEquals(1400L, qwens.results[0].downloads)
            assertNull(qwens.next)

            // every GGUF repo for an empty query, most downloaded first, a page at a time
            val first = fake.hub.search("") { limit = 2 }
            assertEquals(listOf("ggml-org/gemma-3-4b-it-GGUF", "Qwen/Qwen3-4B"), first.results.map { it.id })
            val second = fake.hub.search(checkNotNull(first.next))
            assertEquals(listOf("Qwen/Qwen3-4B"), second.results.map { it.id })
            assertEquals(
                listOf("bartowski/Qwen_Qwen3-4B-GGUF"),
                second.results
                    .single()
                    .repos
                    .map { it.id },
            )
            assertNull(second.next)

            assertEquals(
                listOf("bartowski/Qwen_Qwen3-4B-GGUF", qwen),
                fake.hub
                    .search("") { sort = SearchSort.Likes }
                    .results[0]
                    .repos
                    .map { it.id },
            )
            assertEquals(
                listOf("ggml-org/gemma-3-4b-it-GGUF"),
                fake.hub
                    .search("") { takesImages = true }
                    .results
                    .map { it.id },
            )
            val unsloth = fake.hub.search("") { author = "unsloth" }
            assertEquals(listOf(qwen), unsloth.results.flatMap { result -> result.repos.map { it.id } })
        }

    @Test
    fun scriptedFilesAndSamplingAreReturned() =
        runBlocking {
            val fake =
                FakeLlamaBroHub {
                    repo(qwen) {
                        baseModel = "Qwen/Qwen3-4B"
                        gguf("Qwen3-4B-Q4_K_M.gguf", sizeBytes = 8192, sampling = Sampling { temperature = 0.7f })
                        gguf("Q8_0/Qwen3-4B-Q8_0-00001-of-00002.gguf", sizeBytes = 4096)
                        gguf("Q8_0/Qwen3-4B-Q8_0-00002-of-00002.gguf", sizeBytes = 4096)
                        gguf("mmproj-F16.gguf")
                    }
                    repo("Qwen/Qwen3-4B") { generationConfig("""{"do_sample":true,"temperature":0.6,"top_k":20}""") }
                }

            val files = fake.hub.files(qwen)
            assertEquals(listOf("Q4_K_M", "Q8_0"), files.quantizations.map { it.name })
            assertEquals(listOf(8192L, 8192L), files.quantizations.map { it.sizeBytes })
            assertEquals(2, files.quantizations[1].parts)
            assertTrue(files.hasImageEncoder)
            assertFalse(files.offline)

            val sampling = fake.hub.recommendedSampling(model)
            assertEquals(0.7f, sampling.fromFile?.temperature)
            assertEquals(
                Sampling {
                    temperature = 0.6f
                    topK = 20
                },
                sampling.fromBaseModel,
            )
            assertEquals("Qwen/Qwen3-4B", sampling.baseModel)
        }

    @Test
    fun scriptedDownloadReportsProgressThenCompletes() =
        runBlocking {
            val fake =
                FakeLlamaBroHub {
                    downloadMillis = 800
                    repo(qwen) {
                        gguf("Qwen3-4B-Q4_K_M.gguf", sizeBytes = 64 * 1024)
                        gguf("mmproj-F16.gguf")
                    }
                }

            val updates = fake.hub.download(model).toList()

            assertEquals(DownloadPhase.Complete, updates.last().phase)
            assertEquals(updates.last().bytesTotal, updates.last().bytesDone)
            assertTrue(
                "progress between the start and the end: $updates",
                updates.any { it.phase == DownloadPhase.Downloading && it.bytesDone in 1 until it.bytesTotal },
            )
            assertEquals(DownloadState.Complete, fake.hub.progress(model).value)
            assertTrue(fake.requests.any { it.endsWith("/mmproj-F16.gguf") })
            assertFalse("installed: nothing to update", fake.hub.hasUpdate(model))

            fake.repo(qwen) {
                gguf("Qwen3-4B-Q4_K_M.gguf", sizeBytes = 32 * 1024)
                gguf("mmproj-F16.gguf")
            }
            assertTrue("a repo declared again is a new commit", fake.hub.hasUpdate(model))
        }

    @Test
    fun cancelledDownloadKeepsItsPartialAndResumes() =
        runBlocking {
            FakeLlamaBroHub {
                downloadMillis = 800
                repo(qwen) { gguf("Qwen3-4B-Q4_K_M.gguf", sizeBytes = 64 * 1024) }
            }.use { fake ->
                val total = 64 * 1024L
                fake.hub
                    .download(model)
                    .takeWhile { it.phase != DownloadPhase.Downloading || it.bytesDone == 0L }
                    .collect()

                val partial =
                    withTimeout(5_000) {
                        fake.hub.progress(model).first { it !is DownloadState.Downloading } as DownloadState.Partial
                    }
                assertTrue("a partial is kept: $partial", partial.bytesDone in 1 until total)

                assertEquals(
                    DownloadPhase.Complete,
                    fake.hub
                        .download(model)
                        .toList()
                        .last()
                        .phase,
                )
                assertEquals(DownloadState.Complete, fake.hub.progress(model).value)
                // the resume asked only for the rest of the file (F114-F116)
                val resumed = fake.requests.last { it.startsWith("GET ") && "/resolve/" in it }
                val from = resumed.substringAfter(" bytes=").substringBefore('-').toLong()
                assertTrue("a ranged resume: $resumed", from in 1 until total)
            }
        }

    @Test
    fun scriptedFailureSurfaces() =
        runBlocking {
            val fake =
                FakeLlamaBroHub {
                    repo(qwen) {
                        baseModel = "meta-llama/Gated"
                        gguf("Qwen3-4B-Q4_K_M.gguf")
                    }
                    repo("meta-llama/Gated") { gated = true }
                    repo("meta-llama/Llama-GGUF") {
                        gated = true
                        gguf("Llama-Q4_K_M.gguf")
                    }
                }

            expect<HubException.NotFound> { fake.hub.files("nobody/missing-GGUF") }
            expect<HubException.Unauthorized> { fake.hub.files("meta-llama/Llama-GGUF") }
            // a gated base model is a missing recommendation, not an error
            assertNull(fake.hub.recommendedSampling(model).fromBaseModel)

            fake.rateLimit(retryAfterSeconds = 30)
            assertEquals(30L, expect<HubException.RateLimited> { fake.hub.search("qwen") }.retryAfterSeconds)
            assertEquals(
                1,
                fake.hub
                    .search("qwen")
                    .results.size,
            ) // only the next request was refused

            fake.offline = true
            assertTrue(expect<HubException.Network> { fake.hub.search("qwen") }.retryable)
            fake.offline = false

            fake.freeBytes = 10
            expect<HubException.InsufficientStorage> { fake.hub.download(model).toList() }
            fake.freeBytes = Long.MAX_VALUE
            assertEquals(
                DownloadPhase.Complete,
                fake.hub
                    .download(model)
                    .toList()
                    .last()
                    .phase,
            )
        }

    @Test
    fun fakeHubModelLoadsIntoFakeLlamaBro() =
        runBlocking {
            val fakeHub =
                FakeLlamaBroHub {
                    repo(qwen) {
                        gguf("Qwen3-4B-Q4_K_M.gguf")
                        gguf("mmproj-F16.gguf")
                    }
                }
            val fake = FakeLlamaBro { reply { content("Hello!") } }
            val progress = mutableListOf<LoadProgress>()

            val loaded = fake.llamaBro.loadModel(fakeHub.hub.model("$qwen:Q4_K_M")) { onProgress { progress += it } }
            assertEquals(1, fake.openModels)
            assertTrue(progress.first() is LoadProgress.Fetching)
            assertTrue(progress.last() is LoadProgress.Loading)
            loaded.closeAndJoin()

            // the installed copy loads again with no request at all (D64)
            val before = fakeHub.requests.size
            fake.llamaBro.loadModel(fakeHub.hub.model("$qwen:Q4_K_M")).closeAndJoin()
            assertEquals(before, fakeHub.requests.size)
        }

    private suspend inline fun <reified T : HubException> expect(block: suspend () -> Unit): T {
        try {
            block()
        } catch (expected: HubException) {
            if (expected is T) return expected
            throw expected
        }
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    }
}
