package com.suhel.llamabro.demo.models

import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogTest {
    @Test fun picksAreSwiftWriterDeepInOrder() {
        assertEquals(listOf("Qwen3.5 0.8B", "Gemma 3 4B", "Qwen3.5 4B"), Catalog.picks.map { it.realName })
    }

    @Test fun suggestionsAreTheSixInOrder() {
        assertEquals(
            listOf("Qwen3.5 0.8B", "Gemma 3 4B", "Qwen3.5 4B", "Llama 3.2 3B", "Qwen3.5 9B", "LFM3 Omni 2B"),
            Catalog.suggested.map { it.realName },
        )
    }

    @Test fun everyKeyParsesAndRoundTrips() {
        for (e in Catalog.suggested) assertEquals(e.key, HfModel.parse(e.key.toString()))
    }

    @Test fun everyCatalogFileIsWhatTheSizePickerRecommends() {
        // the picker's Recommended tier is Q4_K_M when a repo has it (Sizes), so a catalog key on another quant would
        // download a file the library cannot name: Gemma 3 4B once showed as "Gemma 3 4B" (F112)
        for (e in Catalog.suggested) assertEquals(e.realName, "Q4_K_M", e.key.toString().substringAfter(':'))
    }

    @Test fun byKeyFindsOnlyCatalogKeys() {
        assertEquals("Qwen3.5 4B", Catalog.byKey(HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"))?.realName)
        assertNull(Catalog.byKey(HfModel.parse("bartowski/Qwen3.5-4B-GGUF:Q4_K_M")))
        assertNull(Catalog.byKey(HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q8_0")))
    }

    @Test fun factsMatchTheSpec() {
        val byName = Catalog.suggested.associateBy { it.realName }
        assertEquals(setOf("Qwen3.5 0.8B", "Qwen3.5 4B", "Qwen3.5 9B"), byName.filterValues { it.thinks }.keys)
        assertEquals(setOf("Llama 3.2 3B", "LFM3 Omni 2B"), byName.filterValues { !it.sees }.keys)
        assertEquals(setOf("LFM3 Omni 2B"), byName.filterValues { it.talks }.keys)
        assertTrue(Catalog.suggested.all { it.about.isNotBlank() && it.maker.isNotBlank() })
    }

    @Test fun sizesEqualPinMd() {
        val rows = File(repoRoot(), "demo/catalog/pin.md").readLines().filter { it.startsWith("| `") }
        assertEquals(6, rows.size)
        for (row in rows) {
            val cells = row.split('|').map { it.trim() }
            val key = cells[1].trim('`')
            val entry = Catalog.byKey(HfModel.parse(key)) ?: error("$key is not in the catalog")
            assertEquals("$key model", cells[4].replace(",", "").toLong(), entry.modelBytes)
            assertEquals("$key mmproj", cells[6].replace(",", "").toLongOrNull() ?: 0L, entry.mmprojBytes)
        }
    }

    @Test fun theEmbedderIsPinnedAndIsNotAChatSuggestion() {
        val embedder = Catalog.embedder
        assertEquals(HfModel.parse("ggml-org/bge-small-en-v1.5-Q8_0-GGUF:Q8_0"), embedder.key)
        // the Library page gets it; a chat can't use it, so it is never among the suggestions
        assertNull(Catalog.byKey(embedder.key))
        assertNull(Catalog.byRepo(embedder.key.repo))
        assertEquals("BGE Small", Names.of(embedder.key))
        val pin = File(repoRoot(), "demo/catalog/pin.md").readText()
        assertTrue(pin.contains("`${embedder.key}`") && pin.contains("%,d".format(embedder.modelBytes)))
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "demo/catalog/pin.md").exists()) dir = dir.parentFile
        return checkNotNull(dir) { "demo/catalog/pin.md not found above ${File("").absolutePath}" }
    }
}
