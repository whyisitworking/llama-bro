package com.suhel.llamabro.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

internal class HfModelTest {
    @Test fun parsesAndRoundTripsReferences() {
        val refs = listOf("org/repo", "org/repo:Q4_K_M", "org/repo#dir/f.gguf")
        for (ref in refs) assertEquals(ref, HfModel.parse(ref).toString())
        assertEquals("org/repo", HfModel.parse("org/repo").repo)
        assertNull(HfModel.parse("org/repo").tag)
        assertEquals("Q4_K_M", HfModel.parse("org/repo:Q4_K_M").tag)
        assertEquals("dir/f.gguf", HfModel.parse("org/repo#dir/f.gguf").file)
        assertEquals("b", HfModel.parse("org/repo:a:b").tag)
    }

    @Test fun malformedReferencesHaveUpstreamMessage() {
        for (ref in listOf("org", "a/b/c:Q4")) {
            try {
                HfModel.parse(ref)
                fail("$ref should fail")
            } catch (e: IllegalArgumentException) {
                assertEquals("invalid HF repo format, expected <user>/<model>[:quant]", e.message)
            }
        }
    }
}
