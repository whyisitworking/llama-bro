package com.suhel.llamabro.rag

import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.testing.FakeLlamaBro
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenCounterTest {
    @Test
    fun estimateIsAThirdOfTheCharactersRoundedUp() =
        runBlocking {
            assertEquals(0, TokenCounter.Estimate.count(""))
            assertEquals(1, TokenCounter.Estimate.count("ab"))
            assertEquals(1, TokenCounter.Estimate.count("abc"))
            assertEquals(2, TokenCounter.Estimate.count("abcd"))
        }

    @Test
    fun modelCountsWhatTheTokenizeRouteReturnsWithSpecialTokens() =
        runBlocking {
            val fake = FakeLlamaBro { raw(200, """{"tokens":[1037,4937,2003]}""") }
            val model = fake.llamaBro.loadModel(ModelSource.file("/m.gguf"))
            assertEquals(3, TokenCounter.Model(model).count("a \"quoted\"\ncat"))
            val request = fake.rawRequests.single()
            assertEquals("tokenize", request.route)
            assertEquals("""{"content":"a \"quoted\"\ncat","add_special":true}""", request.body)
        }
}
