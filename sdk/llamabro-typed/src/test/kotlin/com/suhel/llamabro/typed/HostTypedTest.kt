@file:OptIn(InternalLlamaBroApi::class)

package com.suhel.llamabro.typed

import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.InternalLlamaBroApi
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.Typed
import com.suhel.llamabro.chat.Reasoning
import com.suhel.llamabro.chat.Sampling
import com.suhel.llamabro.hostLlamaBro
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Typed output over the real native core on this machine (CPU) with the e2e model, run by sdk/scripts/check.sh: the
 * grammar from [typedSchema] gives JSON that decodes, with Think off and with Think on (F143). Skipped without
 * `-Pllamabro.host.lib` and `-Pllamabro.host.model`.
 */
class HostTypedTest {
    @Serializable
    data class Person(val name: String, val age: Int)

    @Test
    fun personWithThinkOff() =
        runBlocking {
            val typed =
                model().generate<Person>("A person named Bob aged 30.") {
                    sampling = greedy
                    reasoning = Reasoning { enableThinking = false }
                }
            println("think off: $typed")
            assertEquals(Person("Bob", 30), (typed as? Typed.Value)?.value)
        }

    @Test
    fun personWithThinkOn() =
        runBlocking {
            val typed =
                model().generate<Person>("A person named Bob aged 30.") {
                    sampling = greedy
                    // Qwen3.5 thinks past 1,024 tokens on this prompt; the budget ends its thinking so the JSON follows
                    reasoning =
                        Reasoning {
                            enableThinking = true
                            budgetTokens = 256
                        }
                }
            println("think on: $typed")
            assertEquals(Person("Bob", 30), (typed as? Typed.Value)?.value)
            assertTrue("the model didn't think: $typed", !(typed as Typed.Value).reasoning.isNullOrBlank())
        }

    private companion object {
        val greedy =
            Sampling {
                temperature = 0f
                seed = 1
                maxTokens = 1024
            }

        private var loaded: Model? = null

        suspend fun model(): Model {
            loaded?.let { return it }
            val lib = System.getProperty("llamabro.host.lib")
            val modelPath = System.getProperty("llamabro.host.model")
            assumeTrue("host end-to-end runs from sdk/scripts/check.sh", lib != null && modelPath != null)
            val llama = hostLlamaBro(lib, Files.createTempDirectory("llamabro-typed").toFile())
            return llama
                .loadModel(ModelSource.file(modelPath)) { contextSize = ContextSize.Fixed(4096) }
                .also { loaded = it }
        }

        @JvmStatic
        @AfterClass
        fun close() {
            loaded?.close()
        }
    }
}
