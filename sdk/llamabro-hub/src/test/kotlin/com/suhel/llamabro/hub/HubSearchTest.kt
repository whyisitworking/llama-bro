package com.suhel.llamabro.hub

import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.hub.SearchFixtures.COMMIT
import com.suhel.llamabro.hub.SearchFixtures.ENDPOINT
import com.suhel.llamabro.hub.SearchFixtures.ScriptedTransport
import com.suhel.llamabro.hub.SearchFixtures.gguf
import com.suhel.llamabro.hub.SearchFixtures.hub
import com.suhel.llamabro.hub.SearchFixtures.info
import com.suhel.llamabro.hub.SearchFixtures.refs
import com.suhel.llamabro.hub.SearchFixtures.tree
import com.suhel.llamabro.hub.internal.JvmStorage
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections

/** Search, repo files and recommended sampling over scripted Hub answers (D68, F146). */
internal class HubSearchTest {
    @Test fun quantizedUploadsGroupUnderTheirOriginalAndAFineTuneStaysSeparate() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.json(searchUrl("qwen3.5-4b"), SEARCH_QWEN35)
            val page = hub(transport).search("qwen3.5-4b")

            assertEquals(
                listOf("Qwen/Qwen3.5-4B", "ccore/Qwen3.5-4B-3D-Coder", "someone/Qwen3.5-4B-merge-GGUF"),
                page.results.map { it.id },
            )
            val original = page.results[0]
            assertEquals(
                listOf("unsloth/Qwen3.5-4B-GGUF", "bartowski/Qwen_Qwen3.5-4B-GGUF"),
                original.repos.map { it.id },
            )
            assertEquals(1500L, original.downloads)
            assertEquals(30L, original.likes)
            assertTrue("one upload takes pictures", original.takesImages)
            assertEquals("Qwen/Qwen3.5-4B", original.repos[0].baseModel)
            assertEquals("unsloth", original.repos[0].author)
            // F146: a fine-tune carries `base_model:finetune:`, not `quantized:`, so it is its own entry.
            val fineTune = page.results[1]
            assertEquals(listOf("ccore/Qwen3.5-4B-3D-Coder"), fineTune.repos.map { it.id })
            assertNull(fineTune.repos.single().baseModel)
            assertFalse(fineTune.takesImages)
            assertNull("no Link header, no next page", page.next)
        }

    @Test fun searchAsksForTheFactsItNeedsInOneRequest() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.json(searchUrl("qwen"), "[]")
            hub(transport).search("qwen")
            assertEquals(listOf(searchUrl("qwen")), transport.requests)
            assertTrue(transport.requests.single().endsWith(EXPAND))
        }

    @Test fun searchReadsTaskTagsAndGgufSummary() =
        runBlocking {
            val repo =
                searchRepo(
                    """{"id":"a/b-GGUF","pipeline_tag":"text-generation","tags":["gguf","code"],""" +
                        """"lastModified":"2026-11-12T02:15:52.000Z","gguf":{"total":27320697856,""" +
                        """"architecture":"qwen35","context_length":262144,"chat_template":"{{x}}"}}""",
                )
            assertEquals("text-generation", repo.task)
            assertEquals(listOf("gguf", "code"), repo.tags)
            assertEquals("2026-11-12T02:15:52.000Z", repo.lastModified)
            val gguf = repo.gguf!!
            assertEquals(27320697856L, gguf.parameters)
            assertEquals("qwen35", gguf.architecture)
            assertEquals(262144L, gguf.contextLength)
            assertTrue(gguf.hasChatTemplate)
        }

    @Test fun aRepoWithoutGgufMetadataHasANullSummary() =
        runBlocking {
            assertNull(searchRepo("""{"id":"a/b-GGUF"}""").gguf)
            assertNull(searchRepo("""{"id":"a/b-GGUF","gguf":{"total":0,"architecture":"x"}}""").gguf)
        }

    @Test fun noChatTemplateIsReported() =
        runBlocking {
            val repo = searchRepo("""{"id":"a/b-GGUF","gguf":{"total":5,"architecture":"bert"}}""")
            assertFalse(repo.gguf!!.hasChatTemplate)
            assertNull(repo.gguf!!.contextLength)
        }

    private suspend fun searchRepo(item: String): SearchRepo {
        val transport = ScriptedTransport()
        transport.json(searchUrl("x"), "[$item]")
        return hub(transport)
            .search("x")
            .results
            .single()
            .repos
            .single()
    }

    @Test fun sortAndFiltersBuildTheQuery() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.json(searchUrl("gemma 3"), "[]")
            transport.json(
                "${ENDPOINT}api/models?search=gemma+3&filter=gguf&sort=trendingScore&direction=-1&limit=10$EXPAND" +
                    "&author=ggml-org&pipeline_tag=image-text-to-text",
                "[]",
            )
            transport.json("${ENDPOINT}api/models?filter=gguf&sort=likes&direction=-1&limit=40$EXPAND", "[]")
            transport.json("${ENDPOINT}api/models?filter=gguf&sort=lastModified&direction=-1&limit=40$EXPAND", "[]")
            val client = hub(transport)

            client.search("gemma 3")
            client.search(" gemma 3 ") {
                sort = SearchSort.Trending
                author = "ggml-org"
                takesImages = true
                limit = 10
            }
            client.search("") { sort = SearchSort.Likes }
            client.search("") { sort = SearchSort.RecentlyUpdated }
            assertEquals(4, transport.requests.size)
            assertTrue(transport.unmatched.isEmpty())
        }

    @Test fun paging() =
        runBlocking {
            val transport = ScriptedTransport()
            val second = searchUrl("qwen") + "&cursor=abc"
            transport.json(searchUrl("qwen"), SEARCH_ONE, headers = mapOf("link" to listOf("<$second>; rel=\"next\"")))
            transport.json(
                second,
                SEARCH_TWO,
                headers = mapOf("link" to listOf("<https://elsewhere.test/api/models?cursor=x>; rel=\"next\"")),
            )
            val client = hub(transport)

            val first = client.search("qwen")
            assertEquals(listOf("Qwen/Qwen3-4B"), first.results.map { it.id })
            val cursor = first.next ?: error("a Link header names the next page")
            val next = client.search(cursor)
            assertEquals(listOf("Qwen/Qwen2.5-3B-Instruct"), next.results.map { it.id })
            assertNull("a next page on another host is not followed", next.next)
            assertEquals(listOf(searchUrl("qwen"), second), transport.requests)
        }

    @Test fun tokenNeverLeavesTheEndpointHost() =
        runBlocking {
            val other = Server { exchange -> exchange.reply(200, "[]") }
            val endpoint =
                Server { exchange ->
                    val next = "http://127.0.0.1:${other.port}/api/models?cursor=elsewhere"
                    if (exchange.requestURI.rawQuery.contains("redirect")) {
                        exchange.responseHeaders.add("Location", "http://127.0.0.1:${other.port}/api/models")
                        exchange.reply(302, "")
                    } else {
                        exchange.responseHeaders.add("Link", "<$next>; rel=\"next\"")
                        exchange.reply(200, SEARCH_ONE)
                    }
                }
            try {
                val cache = Files.createTempDirectory("hub-search-token-").toFile()
                val config =
                    HubConfig {
                        this.endpoint = "http://127.0.0.1:${endpoint.port}/"
                        cacheDir = cache
                        token { TOKEN }
                    }
                val client = LlamaBroHub(config, cache, ScriptedTransport(), JvmStorage())

                val page = client.search("qwen")
                assertNull("a cursor to another host is dropped", page.next)
                client.search("redirect")

                assertEquals(listOf(true, true), endpoint.auth)
                assertEquals("the redirect reached the other host", 1, other.auth.size)
                assertEquals("no token across the redirect", listOf(false), other.auth)
            } finally {
                endpoint.stop()
                other.stop()
            }
        }

    @Test fun modelCardStripsFrontMatter() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.text("Qwen/Qwen3.5-4B", "---\nlicense: mit\n---\n\n# Qwen\nHello")
            assertEquals("# Qwen\nHello", hub(transport).modelCard("Qwen/Qwen3.5-4B"))
            transport.text("a/plain", "# Title\n---\nbody")
            assertEquals("# Title\n---\nbody", hub(transport).modelCard("a/plain"))
            transport.text("a/unclosed", "---\nlicense: mit\n# Title")
            assertEquals("---\nlicense: mit\n# Title", hub(transport).modelCard("a/unclosed"))
        }

    @Test fun modelCardIsNullWithoutAReadme() =
        runBlocking { assertNull(hub(ScriptedTransport()).modelCard("Qwen/Qwen3.5-4B")) }

    @Test fun modelCardIsCutAtHalfAMebibyte() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.text("a/big", "x".repeat(600 * 1024))
            assertEquals(512 * 1024, hub(transport).modelCard("a/big")?.length)
        }

    @Test fun modelCardOfAGatedRepoThrowsUnauthorized() =
        runBlocking {
            val endpoint = Server { exchange -> exchange.reply(401, """{"error":"gated"}""") }
            try {
                tokenHub(endpoint.port).modelCard("meta/gated")
                fail("expected Unauthorized")
            } catch (expected: HubException.Unauthorized) {
                assertEquals(401, expected.httpStatus)
            } finally {
                endpoint.stop()
            }
        }

    @Test fun modelCardSendsTheTokenOnlyToTheEndpoint() =
        runBlocking {
            val other = Server { exchange -> exchange.reply(200, "# elsewhere") }
            val endpoint =
                Server { exchange ->
                    if (exchange.requestURI.path.startsWith("/redirect/")) {
                        exchange.responseHeaders.add("Location", "http://127.0.0.1:${other.port}/card.md")
                        exchange.reply(302, "")
                    } else {
                        exchange.reply(200, "# here")
                    }
                }
            try {
                val client = tokenHub(endpoint.port)
                assertEquals("# here", client.modelCard("a/b"))
                client.modelCard("redirect/b")
                assertEquals(listOf(true, true), endpoint.auth)
                assertEquals("no token across the redirect", listOf(false), other.auth)
            } finally {
                endpoint.stop()
                other.stop()
            }
        }

    @Test fun filesSumSplitPartsAndNoteTheImageEncoder() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.listing(
                "unsloth/Qwen3.5-4B-GGUF",
                listOf(
                    "Qwen3.5-4B-Q4_K_M.gguf" to 2_500L,
                    "Qwen3.5-4B-UD-Q8_K_XL.gguf" to 5_000L,
                    "BF16/Qwen3.5-4B-BF16-00001-of-00002.gguf" to 4_000L,
                    "BF16/Qwen3.5-4B-BF16-00002-of-00002.gguf" to 3_000L,
                    "mmproj-F16.gguf" to 600L,
                    "imatrix_unsloth.gguf" to 10L,
                    "README.md" to 5L,
                ),
            )
            val files = hub(transport).files("unsloth/Qwen3.5-4B-GGUF")

            assertEquals(COMMIT, files.commit)
            assertTrue(files.hasImageEncoder)
            assertFalse(files.offline)
            assertEquals(listOf("Q4_K_M", "Q8_K_XL", "BF16"), files.quantizations.map { it.name })
            val split = files.quantizations[2]
            assertEquals(7_000L, split.sizeBytes)
            assertEquals(2, split.parts)
            assertEquals("BF16/Qwen3.5-4B-BF16-00001-of-00002.gguf", split.path)
            assertEquals(HfModel("unsloth/Qwen3.5-4B-GGUF#BF16/Qwen3.5-4B-BF16-00001-of-00002.gguf"), split.model)
            assertEquals(2_500L, files.quantizations[0].sizeBytes)
            assertEquals(1, files.quantizations[0].parts)
        }

    @Test fun eachQuantisationNamesTheEncoderItWouldFetch() =
        runBlocking {
            // LFM3 Omni's repo (D101): a Q8_0 and a BF16 encoder; the -hf rule takes the one nearest the model's bits
            val transport = ScriptedTransport()
            transport.listing(
                "ggml-org/LFM3-Omni-2B-GGUF",
                listOf(
                    "LFM3-Omni-2B-Q4_K_M.gguf" to 1_099L,
                    "LFM3-Omni-2B-Q8_0.gguf" to 1_846L,
                    "LFM3-Omni-2B-BF16.gguf" to 3_466L,
                    "mmproj-LFM3-Omni-2B-BF16.gguf" to 949L,
                    "mmproj-LFM3-Omni-2B-Q8_0.gguf" to 506L,
                ),
            )
            val files = hub(transport).files("ggml-org/LFM3-Omni-2B-GGUF")
            assertEquals(
                mapOf("Q4_K_M" to 506L, "Q8_0" to 506L, "BF16" to 949L),
                files.quantizations.associate { it.name to it.encoderBytes },
            )
            transport.listing(
                "unsloth/Llama-3.2-3B-Instruct-GGUF",
                listOf("Llama-3.2-3B-Instruct-Q4_K_M.gguf" to 2_019L),
            )
            assertEquals(
                0L,
                hub(transport)
                    .files("unsloth/Llama-3.2-3B-Instruct-GGUF")
                    .quantizations
                    .single()
                    .encoderBytes,
            )
        }

    @Test fun generationConfigMapsToSampling() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.model("unsloth/Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf", base = "Qwen/Qwen3-4B")
            transport.json("${ENDPOINT}Qwen/Qwen3-4B/resolve/main/generation_config.json", QWEN3_GENERATION)
            val recommended = hub(transport).recommendedSampling(HfModel("unsloth/Qwen3-4B-GGUF"))

            assertEquals(
                Sampling {
                    temperature = 0.6f
                    topK = 20
                    topP = 0.95f
                },
                recommended.fromBaseModel,
            )
            assertEquals("Qwen/Qwen3-4B", recommended.baseModel)
            assertNull("only the base model has values: the file's are null", recommended.fromFile)
        }

    @Test fun penaltiesAndMinPMap() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.model("bartowski/Qwen2.5-3B-GGUF", "Qwen2.5-3B-Q4_K_M.gguf", base = "Qwen/Qwen2.5-3B-Instruct")
            transport.json(
                "${ENDPOINT}Qwen/Qwen2.5-3B-Instruct/resolve/main/generation_config.json",
                """{"do_sample": true, "temperature": 0.7, "top_p": 0.8, "top_k": 20, "min_p": 0.05,
                   "repetition_penalty": 1.05, "presence_penalty": 1.5, "eos_token_id": [151645, 151643]}""",
            )
            val recommended = hub(transport).recommendedSampling(HfModel("bartowski/Qwen2.5-3B-GGUF"))

            assertEquals(
                Sampling {
                    temperature = 0.7f
                    topP = 0.8f
                    topK = 20
                    minP = 0.05f
                    repeatPenalty = 1.05f
                    presencePenalty = 1.5f
                },
                recommended.fromBaseModel,
            )
        }

    @Test fun doSampleFalseIsGreedy() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.model("someone/Greedy-GGUF", "Greedy-Q4_K_M.gguf", base = "someone/Greedy")
            transport.json(
                "${ENDPOINT}someone/Greedy/resolve/main/generation_config.json",
                """{"do_sample": false, "temperature": 0.9, "top_p": 0.9}""",
            )
            val recommended = hub(transport).recommendedSampling(HfModel("someone/Greedy-GGUF"))

            assertEquals(Sampling { temperature = 0f }, recommended.fromBaseModel)
        }

    @Test fun gatedBaseModelGivesNull() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.model("bartowski/gemma-3-4b-it-GGUF", "gemma-3-4b-it-Q4_K_M.gguf", base = "google/gemma-3-4b-it")
            transport.json(
                "${ENDPOINT}google/gemma-3-4b-it/resolve/main/generation_config.json",
                """{"error": "Access to model google/gemma-3-4b-it is restricted."}""",
                status = 401,
            )
            val cache = Files.createTempDirectory("hub-search-gated-").toFile()

            val recommended = hub(transport, cache).recommendedSampling(HfModel("bartowski/gemma-3-4b-it-GGUF"))
            assertNull(recommended.fromBaseModel)
            assertNull(recommended.fromFile)
            assertEquals("google/gemma-3-4b-it", recommended.baseModel)
            assertNoStubsLeft(cache)
        }

    @Test fun ggufHeaderSamplingIsReadWithBoundedRanges() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.json("${ENDPOINT}api/models/ggml-org/LFM3-Omni-GGUF", info(base = null))
            transport.listing(
                "ggml-org/LFM3-Omni-GGUF",
                listOf("LFM3-Omni-Q4_K_M.gguf" to 0L, "LFM3-Omni-Q8_0.gguf" to 0L),
            )
            transport.gguf("ggml-org/LFM3-Omni-GGUF", "LFM3-Omni-Q4_K_M.gguf", gguf(LFM_HEADER))
            val cache = Files.createTempDirectory("hub-search-header-").toFile()

            val recommended = hub(transport, cache).recommendedSampling(HfModel("ggml-org/LFM3-Omni-GGUF"))

            assertEquals(LFM_SAMPLING, recommended.fromFile)
            assertNull("no base model tag: no base values", recommended.fromBaseModel)
            assertNull(recommended.baseModel)
            assertTrue(
                "read with bounded ranges",
                transport.headerRanges.isNotEmpty() && transport.headerRanges.all { it != null },
            )
            assertNoStubsLeft(cache)
        }

    @Test fun bothSourcesAreReturnedLabelled() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.model("ggml-org/Both-GGUF", "Both-Q4_K_M.gguf", base = "Qwen/Qwen3-4B", header = LFM_HEADER)
            transport.json("${ENDPOINT}Qwen/Qwen3-4B/resolve/main/generation_config.json", QWEN3_GENERATION)

            val recommended = hub(transport).recommendedSampling(HfModel("ggml-org/Both-GGUF"))

            assertEquals("the file's values, what loads by default (D79)", LFM_SAMPLING, recommended.fromFile)
            assertEquals(0.6f, recommended.fromBaseModel?.temperature)
            assertEquals("Qwen/Qwen3-4B", recommended.baseModel)
        }

    @Test fun cachedModelHeaderIsReadWithNoNetworkRequest() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.model("ggml-org/Cached-GGUF", "Cached-Q4_K_M.gguf", base = null, header = LFM_HEADER)
            val cache = Files.createTempDirectory("hub-search-cached-").toFile()
            val client = hub(transport, cache)
            client.download(HfModel("ggml-org/Cached-GGUF")).toList()
            transport.requests.clear()
            transport.attempts = 0

            val recommended = client.recommendedSampling(HfModel("ggml-org/Cached-GGUF"))

            assertEquals(LFM_SAMPLING, recommended.fromFile)
            assertEquals(
                "only the repo's tags are asked for; the header comes from disk (D79)",
                listOf("${ENDPOINT}api/models/ggml-org/Cached-GGUF"),
                transport.requests,
            )
        }

    @Test fun aCachedHeaderWithNoSamplingIsNotReadAgainFromTheHub() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.model("ggml-org/Plain-GGUF", "Plain-Q4_K_M.gguf", base = null)
            val cache = Files.createTempDirectory("hub-search-plain-").toFile()
            val client = hub(transport, cache)
            client.download(HfModel("ggml-org/Plain-GGUF")).toList()
            transport.requests.clear()
            transport.attempts = 0

            val recommended = client.recommendedSampling(HfModel("ggml-org/Plain-GGUF"))

            assertNull(recommended.fromFile)
            assertEquals(
                "the file on disk was read and holds no values; the same file on the Hub holds none either",
                listOf("${ENDPOINT}api/models/ggml-org/Plain-GGUF"),
                transport.requests,
            )
        }

    @Test fun offlineACachedModelStillGivesTheFilesValues() =
        runBlocking {
            for (options in listOf<PlanOptions.() -> Unit>({}, { mmproj = false })) {
                val transport = ScriptedTransport()
                transport.model(
                    "ggml-org/Cached-GGUF",
                    "Cached-Q4_K_M.gguf",
                    base = "Qwen/Qwen3-4B",
                    header = LFM_HEADER,
                )
                // An image encoder, so a copy made without it is not the default selection's copy.
                transport.listing(
                    "ggml-org/Cached-GGUF",
                    listOf("Cached-Q4_K_M.gguf" to 0L, "mmproj-Cached-F16.gguf" to 0L),
                )
                transport.gguf("ggml-org/Cached-GGUF", "Cached-Q4_K_M.gguf", gguf(LFM_HEADER))
                transport.gguf("ggml-org/Cached-GGUF", "mmproj-Cached-F16.gguf", gguf(emptyMap()))
                val cache = Files.createTempDirectory("hub-search-offline-").toFile()
                val client = hub(transport, cache)
                client.download(HfModel("ggml-org/Cached-GGUF"), options).toList()
                // A started update moves refs/main to a newer commit the phone doesn't hold: only the installed
                // record, whatever its options, still names the copy on disk (D64).
                transport.json(
                    "${ENDPOINT}api/models/ggml-org/Cached-GGUF/refs",
                    """{"branches":[{"name":"main","targetCommit":"$NEWER"}]}""",
                )
                transport.json(
                    "${ENDPOINT}api/models/ggml-org/Cached-GGUF/tree/$NEWER?recursive=true",
                    tree(listOf("Cached-Q4_K_M.gguf" to gguf(emptyMap()))),
                )
                client.recordedPlan(HfModel("ggml-org/Cached-GGUF"), options)
                transport.requests.clear()
                transport.attempts = 0
                transport.failWith = HubException.Network("offline", IOException("no route"), true)

                val recommended = client.recommendedSampling(HfModel("ggml-org/Cached-GGUF"))

                assertEquals("the header from disk (D79)", LFM_SAMPLING, recommended.fromFile)
                assertNull("the base model can't be known offline", recommended.fromBaseModel)
                assertNull(recommended.baseModel)
                assertEquals("no request got through", emptyList<String>(), transport.requests)
                assertEquals("only the tag lookup was tried", 1, transport.attempts)
            }
        }

    @Test fun noValuesAnywhereGivesNulls() =
        runBlocking {
            val transport = ScriptedTransport()
            val phi = mapOf<String, Any>("general.name" to "phi")
            transport.model("someone/Phi-4-mini-GGUF", "Phi-4-mini-Q4_K_M.gguf", "microsoft/Phi-4-mini", phi)
            transport.json(
                "${ENDPOINT}microsoft/Phi-4-mini/resolve/main/generation_config.json",
                """{"bos_token_id": 199999, "eos_token_id": [200020, 199999], "do_sample": true}""",
            )

            val recommended = hub(transport).recommendedSampling(HfModel("someone/Phi-4-mini-GGUF"))
            assertNull(recommended.fromFile)
            assertNull(recommended.fromBaseModel)
        }

    @Test fun aRefusedTokenOnTheModelsOwnRepoThrows() =
        runBlocking {
            val endpoint = Server { exchange -> exchange.reply(401, """{"error":"Invalid credentials"}""") }
            try {
                try {
                    tokenHub(endpoint.port).recommendedSampling(HfModel("someone/Private-GGUF"))
                    fail("a configured token the repo refuses is an error, as plan() and files() say")
                } catch (expected: HubException.Unauthorized) {
                    assertEquals(401, expected.httpStatus)
                }
            } finally {
                endpoint.stop()
            }
        }

    @Test fun withATokenAGatedBaseModelStillGivesNull() =
        runBlocking {
            val repo = "bartowski/gemma-3-4b-it-GGUF"
            val file = "gemma-3-4b-it-Q4_K_M.gguf"
            val bytes = gguf(LFM_HEADER)
            val endpoint =
                Server { exchange ->
                    val path = exchange.requestURI.path
                    when {
                        path == "/api/models/$repo" -> exchange.reply(200, info("google/gemma-3-4b-it"))
                        path == "/api/models/$repo/refs" -> exchange.reply(200, refs())
                        path == "/api/models/$repo/tree/$COMMIT" -> exchange.reply(200, tree(listOf(file to bytes)))
                        path == "/$repo/resolve/$COMMIT/$file" -> exchange.reply(200, bytes)
                        else -> exchange.reply(401, """{"error":"gated"}""")
                    }
                }
            try {
                val recommended = tokenHub(endpoint.port).recommendedSampling(HfModel(repo))
                assertNull(
                    "the base model's gated config is no recommendation, not an error",
                    recommended.fromBaseModel,
                )
                assertEquals(LFM_SAMPLING, recommended.fromFile)
            } finally {
                endpoint.stop()
            }
        }

    private fun tokenHub(port: Int): LlamaBroHub {
        val cache = Files.createTempDirectory("hub-search-token-").toFile()
        val config =
            HubConfig {
                endpoint = "http://127.0.0.1:$port/"
                cacheDir = cache
                token { TOKEN }
            }
        return LlamaBroHub(config, cache, ScriptedTransport(), JvmStorage())
    }

    @Test fun networkFailureThrows() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.failWith = HubException.Network("offline", IOException("no route"), true)
            val client = hub(transport)
            try {
                client.recommendedSampling(HfModel("unsloth/Qwen3-4B-GGUF"))
                fail("a network failure is an error, not a missing recommendation")
            } catch (expected: HubException.Network) {
                assertTrue(expected.retryable)
            }
            try {
                client.search("qwen")
                fail("search reports the failure")
            } catch (expected: HubException.Network) {
                assertTrue(expected.retryable)
            }
        }

    @Test fun refusedSearchIsTyped() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.json(searchUrl("qwen"), "{}", status = 429, headers = mapOf("retry-after" to listOf("7")))
            try {
                hub(transport).search("qwen")
                fail("rate limited")
            } catch (expected: HubException.RateLimited) {
                assertEquals(7L, expected.retryAfterSeconds)
            }
        }

    @Test fun anUnreadableOrCorruptLocalHeaderFallsBackToTheHub() =
        runBlocking {
            // A directory where the file was: nothing is installed any more, so there is no local header to read. Then
            // a header that doesn't parse, and one that ends early, both at the recorded size, so the copy still counts
            // as installed and the local read fails. No read permission is used: a root run ignores it.
            val spoilers =
                listOf<(File) -> Unit>(
                    { blob ->
                        Files.delete(blob.toPath())
                        File(blob, "not-a-gguf").apply { parentFile!!.mkdirs() }.writeText("x")
                    },
                    { RandomAccessFile(it, "rw").use { raf -> raf.write("XXXX".toByteArray()) } },
                    { blob ->
                        RandomAccessFile(blob, "rw").use { raf ->
                            raf.seek(GGUF_METADATA_COUNT_OFFSET)
                            raf.write(ByteArray(Long.SIZE_BYTES) { if (it == 2) 1 else 0 }) // 65,536 pairs
                        }
                    },
                )
            for (spoil in spoilers) {
                val transport = ScriptedTransport()
                transport.model("ggml-org/Cached-GGUF", "Cached-Q4_K_M.gguf", base = null, header = LFM_HEADER)
                val cache = Files.createTempDirectory("hub-search-spoilt-").toFile()
                val client = hub(transport, cache)
                client.download(HfModel("ggml-org/Cached-GGUF")).toList()
                val blob =
                    cache.walkTopDown().single {
                        it.isFile && it.parentFile.name == "blobs" && !it.name.endsWith(".downloadInProgress")
                    }
                spoil(blob)
                transport.requests.clear()

                val recommended = client.recommendedSampling(HfModel("ggml-org/Cached-GGUF"))

                assertEquals("the Hub's copy of the header", LFM_SAMPLING, recommended.fromFile)
                assertTrue(transport.requests.toString(), transport.requests.any { "/resolve/" in it })
            }
        }

    @Test fun anErrorAnswerIsNotOffline() =
        runBlocking {
            val transport = ScriptedTransport()
            transport.model("ggml-org/Cached-GGUF", "Cached-Q4_K_M.gguf", base = null, header = LFM_HEADER)
            val client = hub(transport)
            client.download(HfModel("ggml-org/Cached-GGUF")).toList()
            transport.json("${ENDPOINT}api/models/ggml-org/Cached-GGUF", """{"error":"boom"}""", status = 503)
            try {
                client.recommendedSampling(HfModel("ggml-org/Cached-GGUF"))
                fail("a 5xx is the Hub's answer: thrown, not read as offline")
            } catch (expected: HubException.Network) {
                assertTrue(expected.retryable)
            }
        }

    private fun assertNoStubsLeft(cache: File) {
        val left = cache.walkTopDown().filter { it.isFile && it.name.endsWith(".gguf") }.toList()
        assertEquals("no header stub stays behind", emptyList<File>(), left)
    }

    /** A local HTTP server recording whether each request carried `Authorization`. */
    private class Server(handle: (HttpExchange) -> Unit) {
        val auth: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port

        init {
            server.createContext("/") { exchange ->
                auth += exchange.requestHeaders.containsKey("Authorization")
                handle(exchange)
            }
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private companion object {
        /** After the magic, the version and the tensor count. */
        const val GGUF_METADATA_COUNT_OFFSET = 16L
        val NEWER = "d".repeat(40)
        val TOKEN = "hf_" + "a".repeat(34)

        const val EXPAND =
            "&expand[]=downloads&expand[]=likes&expand[]=tags&expand[]=pipeline_tag&expand[]=lastModified&expand[]=gguf"

        fun searchUrl(query: String): String =
            "${ENDPOINT}api/models?search=${query.replace(
                " ",
                "+",
            )}&filter=gguf&sort=downloads&direction=-1&limit=40$EXPAND"

        val LFM_HEADER: Map<String, Any> =
            mapOf(
                "general.sampling.temp" to 0f,
                "general.sampling.top_k" to 1,
                "general.sampling.penalty_repeat" to 1.1f,
                "general.sampling.penalty_last_n" to 64,
            )

        val LFM_SAMPLING =
            Sampling {
                temperature = 0f
                topK = 1
                repeatPenalty = 1.1f
                repeatLastN = 64
            }

        fun HttpExchange.reply(status: Int, body: String) = reply(status, body.toByteArray())

        fun HttpExchange.reply(status: Int, bytes: ByteArray) {
            sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
            close()
        }

        val SEARCH_QWEN35 =
            """
            [
              {"id":"unsloth/Qwen3.5-4B-GGUF","downloads":1000,"likes":20,"pipeline_tag":"image-text-to-text",
               "tags":["gguf","base_model:Qwen/Qwen3.5-4B","base_model:quantized:Qwen/Qwen3.5-4B"]},
              {"id":"ccore/Qwen3.5-4B-3D-Coder","downloads":700,"likes":3,"pipeline_tag":"text-generation",
               "tags":["gguf","base_model:Qwen/Qwen3.5-4B","base_model:finetune:Qwen/Qwen3.5-4B"]},
              {"id":"bartowski/Qwen_Qwen3.5-4B-GGUF","downloads":500,"likes":10,
               "tags":["gguf","base_model:Qwen/Qwen3.5-4B","base_model:quantized:qwen/qwen3.5-4b"]},
              {"id":"someone/Qwen3.5-4B-merge-GGUF","downloads":5,"likes":0,
               "tags":["gguf","base_model:merge:Qwen/Qwen3.5-4B"]}
            ]
            """.trimIndent()

        const val SEARCH_ONE =
            """[{"id":"unsloth/Qwen3-4B-GGUF","downloads":9,"likes":1,"tags":["base_model:quantized:Qwen/Qwen3-4B"]}]"""

        const val SEARCH_TWO =
            """[{"id":"bartowski/Qwen2.5-3B-Instruct-GGUF","downloads":8,"likes":1,
                "tags":["base_model:quantized:Qwen/Qwen2.5-3B-Instruct"]}]"""

        // Qwen/Qwen3-4B's generation_config.json as F146 read it.
        val QWEN3_GENERATION =
            """
            {"bos_token_id": 151643, "do_sample": true, "eos_token_id": [151645, 151643], "pad_token_id": 151643,
             "temperature": 0.6, "top_k": 20, "top_p": 0.95, "transformers_version": "4.51.0"}
            """.trimIndent()
    }
}
