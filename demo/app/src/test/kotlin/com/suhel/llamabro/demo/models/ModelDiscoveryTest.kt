package com.suhel.llamabro.demo.models

import com.suhel.llamabro.hub.HubException
import com.suhel.llamabro.hub.testing.FakeLlamaBroHub
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Search rows and a repo's files from the SDK's search (D68), named and lined as the app names models (§5). */
class ModelDiscoveryTest {
    private val fake =
        FakeLlamaBroHub {
            repo("unsloth/Qwen3.5-4B-GGUF") {
                baseModel = "Qwen/Qwen3.5-4B"
                downloads = 900
                takesImages = true
                gguf("Qwen3.5-4B-Q4_K_M.gguf")
                gguf("Qwen3.5-4B-UD-Q4_K_XL.gguf")
                gguf("Q8_0/Qwen3.5-4B-Q8_0-00001-of-00002.gguf", sizeBytes = 4096)
                gguf("Q8_0/Qwen3.5-4B-Q8_0-00002-of-00002.gguf", sizeBytes = 4096)
                gguf("mmproj-F16.gguf", sizeBytes = ENCODER_BYTES)
            }
            repo("bartowski/Qwen_Qwen3.5-4B-GGUF") {
                baseModel = "Qwen/Qwen3.5-4B"
                downloads = 1_500
                gguf("Qwen_Qwen3.5-4B-Q4_K_M.gguf")
            }
            repo("someone/Tiny-0.5B-GGUF") {
                downloads = 50
                gguf("Tiny-0.5B-Q4_K_M.gguf")
            }
            repo("someone/Mid-7B-GGUF") {
                downloads = 40
                gguf("Mid-7B-Q4_K_M.gguf")
            }
            repo("someone/Mystery-GGUF") {
                downloads = 30
                gguf("Mystery-Q4_K_M.gguf")
            }
            repo("alice/Foo-1B-GGUF") {
                downloads = 10
                gguf("Foo-1B-Q4_K_M.gguf")
            }
            repo("bob/Foo-1B-GGUF") {
                downloads = 5
                takesImages = true
                gguf("Foo-1B-Q4_K_M.gguf")
            }
            repo("unsloth/gemma-3-4b-it-GGUF") {
                baseModel = "google/gemma-3-4b-it"
                downloads = 20
                takesImages = true
                gguf("gemma-3-4b-it-Q4_K_M.gguf")
            }
        }
    private val discovery = ModelDiscovery(fake.hub)

    @After fun close() = fake.close()

    private fun search(query: String) = runBlocking { discovery.search(query) }

    @Test fun uploadsOfOneModelCollapseIntoOneRow() {
        val rows = search("qwen3.5")
        val qwen = rows.single()
        // two uploads of Qwen3.5 4B, one row: the catalog's upload stands for it, and the downloads add up
        assertEquals("Qwen3.5 4B", qwen.displayName)
        assertEquals("unsloth/Qwen3.5-4B-GGUF", qwen.repo)
        assertEquals(2_400L, qwen.downloads)
        assertEquals("unsloth/Qwen3.5-4B-GGUF", qwen.catalog?.key?.repo)
        // keyed as the suggestion's row is, so a download started from either shows on both
        assertEquals("qwen3.5-4b", qwen.groupKey)
    }

    @Test fun aModelTheCatalogDoesNotKnowIsNamedFromItsRepoAndStoodForByItsMostDownloadedUpload() {
        val rows = search("someone").associateBy { it.repo }
        assertEquals("Tiny 0.5B", rows.getValue("someone/Tiny-0.5B-GGUF").displayName)
        assertEquals("someone", rows.getValue("someone/Tiny-0.5B-GGUF").author)
        assertNull(rows.getValue("someone/Tiny-0.5B-GGUF").catalog)
        // most downloaded first, as the Hub sorts them
        assertEquals(listOf(50L, 40L, 30L), search("someone").map { it.downloads })
    }

    @Test fun resultsTheHubLeavesApartButThatShareANameAreOneRow() {
        // no quantized-from tag, so the SDK keeps them apart; a list keys its rows by name, so they become one
        val foo = search("foo").single()
        assertEquals("Foo 1B", foo.displayName)
        assertEquals("alice/Foo-1B-GGUF", foo.repo)
        assertEquals(15L, foo.downloads)
        assertTrue(foo.sees)
        assertEquals("foo-1b", foo.groupKey)
        // the busier upload doesn't see, the other does: the row's icon follows what it says, so its fit counts a
        // reader
        assertEquals(listOf(Capability.SeesImages), foo.capabilities)
    }

    @Test fun aMergedRowCanDoWhatAnyOfItsUploadsCanInARowsOrder() {
        val busier =
            SearchModel(
                groupKey = "x",
                name = "X",
                repo = "a/X-GGUF",
                author = "a",
                downloads = 100,
                sees = false,
                catalog = null,
                capabilities = listOf(Capability.Coding),
            )
        val other =
            busier.copy(
                repo = "b/X-GGUF",
                downloads = 1,
                sees = true,
                capabilities = listOf(Capability.SeesImages, Capability.Thinks),
            )
        val row = SearchRows.merge(listOf(busier, other)).single()
        assertEquals("a/X-GGUF", row.repo)
        assertTrue(row.sees)
        assertEquals(listOf(Capability.Thinks, Capability.SeesImages, Capability.Coding), row.capabilities)
    }

    @Test fun theOriginalOfAnUploadIsTheHubsTag() {
        assertEquals("google/gemma-3-4b-it", runBlocking { discovery.baseModel("unsloth/gemma-3-4b-it-GGUF") })
        assertNull(runBlocking { discovery.baseModel("alice/Foo-1B-GGUF") })
    }

    @Test fun seesImagesFromPipelineTag() {
        // the catalog says what a model it knows does; for any other the Hub's task says it
        assertTrue(search("gemma").single().sees)
        assertFalse(search("tiny").single().sees)
    }

    @Test fun filesListEveryQuantisationOnceWithoutTheImageReader() {
        val files = runBlocking { discovery.files("unsloth/Qwen3.5-4B-GGUF") }
        assertEquals(listOf("Q4_K_M", "Q4_K_XL", "Q8_0"), files.map { it.quant }.sorted())
        // a split file is one entry with every part's bytes
        assertEquals(8192L, files.single { it.quant == "Q8_0" }.bytes)
        // and the sheet's Recommended is found among the hub's names
        assertEquals("Q4_K_M", Sizes.tiers(files)[SizeTier.Recommended]?.quant)
    }

    @Test fun aFileCarriesTheEncoderTheHubFetchesWithIt() {
        val withReader = runBlocking { discovery.files("unsloth/Qwen3.5-4B-GGUF") }
        assertEquals(ENCODER_BYTES.toLong(), withReader.single { it.quant == "Q4_K_M" }.encoderBytes)
        val without = runBlocking { discovery.files("someone/Tiny-0.5B-GGUF") }
        assertEquals(0L, without.single().encoderBytes)
    }

    @Test fun makerIsTheOwnerOfTheOriginal() {
        fake.repo("someone/Qwen2.5-Coder-7B-GGUF") {
            baseModel = "Qwen/Qwen2.5-Coder-7B"
            gguf("Qwen2.5-Coder-7B-Q4_K_M.gguf")
        }
        assertEquals("Alibaba Qwen", search("coder").single().maker)
        assertNull(search("mystery").single().maker)
        assertEquals("Alibaba Qwen", search("qwen3.5").single().maker)
    }

    @Test fun capabilitiesComeFromTaskTagsAndName() {
        fake.repo("someone/Coder-7B-GGUF") { gguf("Coder-7B-Q4_K_M.gguf") }
        fake.repo("someone/Tagged-7B-GGUF") {
            tags = listOf("code")
            gguf("Tagged-7B-Q4_K_M.gguf")
        }
        fake.repo("someone/Looker-7B-GGUF") {
            task = "image-text-to-text"
            gguf("Looker-7B-Q4_K_M.gguf")
        }
        fake.repo("someone/Vec-GGUF") {
            task = "sentence-similarity"
            gguf("Vec-Q4_K_M.gguf")
        }
        fake.repo("someone/Encoder-GGUF") {
            architecture = "nomic-bert"
            parameters = 137_000_000
            gguf("Encoder-Q4_K_M.gguf")
        }
        val rows = search("someone").associateBy { it.repo }
        assertEquals(listOf(Capability.Coding), rows.getValue("someone/Coder-7B-GGUF").capabilities)
        assertEquals(listOf(Capability.Coding), rows.getValue("someone/Tagged-7B-GGUF").capabilities)
        assertEquals(listOf(Capability.SeesImages), rows.getValue("someone/Looker-7B-GGUF").capabilities)
        assertEquals(listOf(Capability.Embeddings), rows.getValue("someone/Vec-GGUF").capabilities)
        assertEquals(listOf(Capability.Embeddings), rows.getValue("someone/Encoder-GGUF").capabilities)
        // the catalog says what its models do, in display order
        val omni = Catalog.suggested.first { it.talks }
        fake.repo(omni.key.repo) { gguf("LFM3-Omni-2B-Q4_K_M.gguf") }
        assertEquals(listOf(Capability.Talks), search("omni").single().capabilities)
        val qwen = search("qwen3.5").single()
        assertEquals(listOf(Capability.Thinks, Capability.SeesImages), qwen.capabilities)
    }

    @Test fun aModelWithoutAChatTemplateCannotChat() {
        fake.repo("someone/Bare-7B-GGUF") {
            parameters = 7_000_000_000
            chatTemplate = false
            gguf("Bare-7B-Q4_K_M.gguf")
        }
        assertFalse(search("bare").single().canChat)
        assertTrue(search("tiny").single().canChat)
    }

    @Test fun anEmbeddingModelWithATemplateCannotChat() {
        fake.repo("Qwen/Qwen3-Embedding-0.6B-GGUF") {
            architecture = "qwen3"
            parameters = 600_000_000
            gguf("Qwen3-Embedding-0.6B-Q4_K_M.gguf")
        }
        assertFalse(search("embedding").single().canChat)
    }

    @Test fun parametersAndActiveParametersComeFromTheHubsHeader() {
        fake.repo("someone/Big-35B-A3B-GGUF") {
            architecture = "qwen35moe"
            parameters = 35_000_000_000
            gguf("Big-35B-A3B-Q4_K_M.gguf")
        }
        val row = search("big").single()
        assertEquals(35_000_000_000L, row.parameters)
        assertEquals(3_000_000_000L, row.activeParameters)
        assertNull(search("tiny").single().activeParameters)
    }

    @Test fun anOfflineSearchSaysSo() {
        fake.offline = true
        try {
            search("qwen")
            fail("expected a failure")
        } catch (e: HubException) {
            assertTrue(e.offline)
            assertTrue(e.retryable)
        }
    }

    @Test fun aHubThatAnswersBadlyIsNotOffline() {
        fake.rateLimit()
        try {
            search("qwen")
            fail("expected a failure")
        } catch (e: HubException) {
            assertFalse(e.offline)
        }
    }

    @Test fun uploadsOfOneModelDifferingInPackagingWordsAreOneRow() =
        runBlocking {
            val hub =
                FakeLlamaBroHub {
                    repo("x/gemma-4-E4B-it-qat-q4_0-unquantized-GGUF") {
                        downloads = 5
                        gguf("gemma-4-E4B-it-Q4_K_M.gguf")
                    }
                    repo("y/gemma-4-E4B-it-GGUF") {
                        downloads = 9
                        gguf("gemma-4-E4B-it-Q4_K_M.gguf")
                    }
                }
            val rows = ModelDiscovery(hub.hub).search("gemma-4")
            assertEquals(1, rows.size)
            assertEquals("Gemma 4 E4B", rows.single().displayName)
            assertEquals(2, rows.single().repos.size)
        }

    @Test fun emptyResultsAreEmptyNotError() = assertEquals(emptyList<SearchModel>(), search("zzzzzz"))

    @Test fun aMergedRowKeepsTheCatalogsRowEvenWhenAnotherIsMoreDownloaded() {
        val deep = Catalog.suggested.first { it.sees }
        val known =
            SearchModel(
                groupKey = "x",
                name = deep.realName,
                repo = deep.key.repo,
                author = "unsloth",
                downloads = 10,
                sees = true,
                catalog = deep,
            )
        val busier = known.copy(name = "X", repo = "someone/X-GGUF", downloads = 100, catalog = null)
        val row = SearchRows.merge(listOf(busier, known)).single()
        assertEquals(known.copy(downloads = 110), row)
    }

    @Test fun anOriginalOpensItsUploads() {
        val target = runBlocking { discovery.related("https://huggingface.co/Qwen/Qwen3.5-4B") }
        val model = (target as LinkTarget.Card).model
        assertEquals(setOf("unsloth/Qwen3.5-4B-GGUF", "bartowski/Qwen_Qwen3.5-4B-GGUF"), model.repos.toSet())
    }

    @Test fun aGgufRepoOpensItself() {
        val target = runBlocking { discovery.related("https://hf.co/someone/Mid-7B-GGUF/tree/main") }
        assertEquals("someone/Mid-7B-GGUF", (target as LinkTarget.Card).model.repo)
    }

    @Test fun aRepoWithNoGgufOpensTheBrowser() {
        val url = "https://huggingface.co/nobody/Nothing-7B"
        assertEquals(LinkTarget.Browser(url), runBlocking { discovery.related(url) })
        val other = "https://example.com/page"
        assertEquals(LinkTarget.Browser(other), runBlocking { discovery.related(other) })
    }

    @Test fun offlineOpensTheBrowser() {
        fake.offline = true
        val url = "https://huggingface.co/Qwen/Qwen3.5-4B"
        assertEquals(LinkTarget.Browser(url), runBlocking { discovery.related(url) })
    }
}

private const val ENCODER_BYTES = 2048
