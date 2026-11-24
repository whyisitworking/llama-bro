package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.ModelInfo
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.demo.models.Catalog
import com.suhel.llamabro.hub.HfModel
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkingTest {
    @Test fun eachLevelsReasoning() {
        assertEquals(false, Thinking.reasoning(Think.Off).enableThinking)
        val quick = Thinking.reasoning(Think.Quick)
        assertEquals(true, quick.enableThinking)
        assertEquals(512, quick.budgetTokens)
        val balanced = Thinking.reasoning(Think.Balanced)
        assertEquals(true, balanced.enableThinking)
        assertEquals(2_048, balanced.budgetTokens)
        val deep = Thinking.reasoning(Think.Deep)
        assertEquals(true, deep.enableThinking)
        assertNull(deep.budgetTokens)
    }

    private fun infoOf(): ModelInfo =
        runBlocking {
            val model =
                FakeLlamaBro().llamaBro.loadModel(
                    ModelSource
                        .file("/fake.gguf"),
                )
            model.info
        }

    @Test fun canThinkForACatalogModel() {
        val swift = Catalog.byKey(HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"))!!
        assertEquals(swift.thinks, Thinking.canThink(infoOf(), swift))
    }

    @Test fun canThinkIsFalseForNone() {
        // the fake model has no thinking template, no capability flag, and no catalog entry
        assertFalse(Thinking.canThink(infoOf(), null))
    }
}
