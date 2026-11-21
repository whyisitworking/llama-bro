package com.suhel.llamabro.demo.models

import org.junit.Assert.assertEquals
import org.junit.Test

/** The cleaned names and makers a row shows (docs/design/models-page.md §3). */
class NamesTest {
    @Test fun packagingWordsAreDropped() {
        assertEquals("Gemma 4 E4B", Names.display("gemma-4-E4B-it-qat-q4_0-unquantized-GGUF"))
        assertEquals("Qwen3.5 4B", Names.display("Qwen3.5-4B-GGUF"))
        assertEquals("Llama 3.2 3B", Names.display("Llama-3.2-3B-Instruct-Q4_K_M-GGUF"))
        assertEquals("Foo 7B", Names.display("Foo-7B-IQ4_XS.gguf"))
        assertEquals("Bar 2B", Names.display("Bar-2B-BF16"))
    }

    @Test fun theNamesOwnCasingAndSeparatorsAreKept() {
        assertEquals("Qwen3 30B A3B", Names.display("Qwen3-30B-A3B"))
        assertEquals("TinyLlama 1.1B Chat v1.0", Names.display("TinyLlama-1.1B-Chat-v1.0"))
        assertEquals("Phi 4 mini", Names.display("phi_4-mini"))
    }

    @Test fun knownOrganisationsGetTheirOwnSpelling() {
        assertEquals("Google", Names.maker("google"))
        assertEquals("Alibaba Qwen", Names.maker("Qwen"))
        assertEquals("Meta", Names.maker("meta-llama"))
        assertEquals("Mistral AI", Names.maker("mistralai"))
        assertEquals("Hugging Face", Names.maker("HuggingFaceTB"))
        assertEquals("xtools-at", Names.maker("xtools-at"))
    }

    @Test fun instructionTokensGoAndParameterCountsAreUpperCase() {
        // "Gemma 4 12B it", "Gemma 3 1b it"
        assertEquals("Gemma 4 12B", Names.display("gemma-4-12B-it-GGUF"))
        assertEquals("Gemma 3 1B", Names.display("gemma-3-1b-it-GGUF"))
        assertEquals("Qwen2.5 Coder 0.5B", Names.display("Qwen2.5-Coder-0.5B-Instruct-GGUF"))
        assertEquals("Gemma 3n E2B", Names.display("gemma-3n-e2b-it-GGUF"))
        // the key is unchanged: an instruct upload and its base stay two models
        assertEquals("gemma-3-1b-it", Names.key("gemma-3-1b-it-GGUF"))
    }
}
