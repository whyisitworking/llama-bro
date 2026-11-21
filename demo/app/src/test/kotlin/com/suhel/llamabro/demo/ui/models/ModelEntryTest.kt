package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.demo.models.Guess
import com.suhel.llamabro.demo.models.SearchModel
import com.suhel.llamabro.hub.HfModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A row's size and names, from what each source knows. */
class ModelEntryTest {
    private fun result(name: String, parameters: Long? = null, sees: Boolean = false) =
        SearchModel(
            groupKey = name.lowercase(),
            name = name,
            repo = "someone/$name-GGUF",
            author = "someone",
            downloads = 1_200_000,
            sees = sees,
            catalog = null,
            parameters = parameters,
        )

    @Test fun aSearchRowsSizeIsTheGuessAndACatalogRowsIsExact() {
        val guessed = ModelEntry.of(result("Phi", parameters = 4_000_000_000, sees = true))
        assertEquals(
            SizeText.About(
                Guess.recommendedBytes(4_000_000_000) + Guess.readerBytes(4_000_000_000),
                Guess.recommendedBytes(4_000_000_000),
            ),
            guessed.size,
        )
        assertNull("no parameter count, no size rather than a wrong one", ModelEntry.of(result("Phi")).size)
        // a count in the name stands in for the header's
        assertEquals(
            SizeText.About(Guess.recommendedBytes(7_000_000_000), Guess.recommendedBytes(7_000_000_000)),
            ModelEntry.of(result("Qwen2.5 Coder 7B")).size,
        )
        val entry = Catalog.suggested[2]
        assertEquals(SizeText.Exact(entry.modelBytes + entry.mmprojBytes, entry.modelBytes), ModelEntry.of(entry).size)
    }

    @Test fun namesAreCleanedAndMakersMapped() {
        assertEquals("Google", ModelEntry.of(result("Gemma").copy(maker = "google")).maker)
        val bare = ModelEntry.downloading(HfModel.parse("someone/gemma-4-E4B-it-qat-q4_0-GGUF:Q4_0"), null)
        assertEquals("Gemma 4 E4B", bare.name)
    }

    // recent searches
}
