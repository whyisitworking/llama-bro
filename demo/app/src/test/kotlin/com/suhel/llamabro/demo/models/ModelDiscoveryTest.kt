package com.suhel.llamabro.demo.models

import com.suhel.llamabro.DeviceKind
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
    private val discovery =
        ModelDiscovery(fake.hub) { DeviceFacts(8L shl 30, DeviceKind.Npu) }

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
    }

    @Test fun seesImagesFromPipelineTag() {
        // the catalog says what a model it knows does; for any other the Hub's task says it
        assertTrue(search("gemma").single().sees)
        assertFalse(search("tiny").single().sees)
    }

    @Test fun plainLineFromParameterCount() {
        val lines = search("someone").associate { it.repo to it.line }
        assertEquals("Tiny and quick, for everyday questions", lines.getValue("someone/Tiny-0.5B-GGUF"))
        assertEquals("Smarter, but heavy for a phone", lines.getValue("someone/Mid-7B-GGUF"))
        assertEquals("A model from someone", lines.getValue("someone/Mystery-GGUF"))
        assertEquals("Balanced, a good all-rounder", SearchRows.plainLine("Phi-4-mini-3.8B", "microsoft"))
        assertEquals("Needs a computer, not a phone", SearchRows.plainLine("Llama-3.3-70B", "meta"))
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

    @Test fun aQuickFitCountsTheRecommendedFilesEncoder() {
        val phone = DeviceFacts(8L shl 30, DeviceKind.Npu)
        // alone the file leaves room in 8 GiB; with its encoder, none (D101)
        val big = RepoFile("Big-Q4_K_M.gguf", "Q4_K_M", 8_000_000_000, encoderBytes = 900_000_000)
        val sized =
            object : ModelDiscovery(fake.hub, { phone }) {
                override suspend fun files(repo: String): List<RepoFile> = listOf(big)
            }
        val fit = runBlocking { sized.quickFit("someone/Big-GGUF", catalog = null) }
        assertEquals(Fits.quick(8_900_000_000, 8_000_000_000, phone), fit)
        assertEquals(FitLabel.TooBig, fit?.label)
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

    @Test fun boundariesOfThePlainLine() {
        assertEquals("Tiny and quick, for everyday questions", SearchRows.plainLine("Foo-1.4B", "x"))
        assertEquals("Balanced, a good all-rounder", SearchRows.plainLine("Foo-1.5B", "x"))
        assertEquals("Balanced, a good all-rounder", SearchRows.plainLine("Foo-4.9b", "x"))
        assertEquals("Smarter, but heavy for a phone", SearchRows.plainLine("Foo-5B", "x"))
        assertEquals("Needs a computer, not a phone", SearchRows.plainLine("Foo-10B", "x"))
        assertEquals("Needs a computer, not a phone", SearchRows.plainLine("Qwen3-30B-A3B", "x"))
        assertEquals("A model from x", SearchRows.plainLine("Foo", "x"))
    }

    @Test fun displayNameKeepsCasingAndSpacesTheSeparators() {
        assertEquals("Qwen3 30B A3B", SearchRows.displayName("Qwen3-30B-A3B"))
        assertEquals("TinyLlama 1.1B Chat v1.0", SearchRows.displayName("TinyLlama-1.1B-Chat-v1.0"))
        assertEquals("Phi 4 mini", SearchRows.displayName("phi_4-mini"))
    }

    @Test fun emptyResultsAreEmptyNotError() = assertEquals(emptyList<SearchModel>(), search("zzzzzz"))

    @Test fun aMergedRowKeepsTheCatalogsRowEvenWhenAnotherIsMoreDownloaded() {
        val deep = Catalog.suggested.first { it.sees }
        val known =
            SearchModel(
                groupKey = "x",
                displayName = deep.realName,
                repo = deep.key.repo,
                author = "unsloth",
                downloads = 10,
                sees = true,
                line = deep.line,
                catalog = deep,
            )
        val busier = known.copy(displayName = "X", repo = "someone/X-GGUF", downloads = 100, line = "?", catalog = null)
        val row = SearchRows.merge(listOf(busier, known)).single()
        assertEquals(known.copy(downloads = 110), row)
    }
}

private const val ENCODER_BYTES = 2048
