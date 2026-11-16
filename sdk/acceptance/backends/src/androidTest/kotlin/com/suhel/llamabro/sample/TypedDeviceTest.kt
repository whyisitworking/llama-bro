package com.suhel.llamabro.sample

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.Typed
import com.suhel.llamabro.typed.generate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Typed output on the phone (D63, F143): llama-server's grammar gives clean JSON that decodes to a `Person`, with
 * Think off and with Think on. Runs only when asked, attended, with the model's file name in the app's files:
 *   ANDROID_SERIAL=<serial> ./gradlew -p sdk :acceptance:backends:connectedFullDebugAndroidTest \
 *     -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.suhel.llamabro.sample.TypedDeviceTest \
 *     -Pandroid.testInstrumentationRunnerArguments.typed=Qwen3.5-4B-Q4_K_M.gguf
 * Replies go to logcat under "Typed" and to filesDir/typed.log.
 */
@RunWith(AndroidJUnit4::class)
class TypedDeviceTest {
    @Serializable
    data class Person(val name: String, val age: Int)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var model: Model

    @Before fun load() =
        runBlocking {
            val name = InstrumentationRegistry.getArguments().getString("typed")
            assumeTrue("a device check: pass the runner argument typed=<file.gguf>", name != null)
            val file = File(context.filesDir, name!!)
            assumeTrue("missing model ${file.path}", file.exists())
            model =
                LlamaBro.create(context).loadModel(ModelSource.file(file.path)) {
                    contextSize = ContextSize.Fixed(CONTEXT)
                }
            log("---- $name on ${model.loadReport.device}")
        }

    @After fun close() =
        runBlocking {
            if (::model.isInitialized) model.closeAndJoin()
        }

    private fun log(line: String) {
        Log.i("Typed", line)
        File(context.filesDir, "typed.log").appendText(line + "\n")
    }

    @Test fun personWithThinkOff() =
        runBlocking {
            val typed =
                model.generate<Person>(PROMPT) {
                    sampling {
                        temperature = 0f
                        maxTokens = LIMIT
                    }
                    reasoning { enableThinking = false }
                }
            log("think off: $typed")
            assertEquals(Person("Bob", 30), (typed as? Typed.Value)?.value)
        }

    @Test fun personWithThinkOn() =
        runBlocking {
            val typed =
                model.generate<Person>(PROMPT) {
                    sampling {
                        temperature = 0f
                        maxTokens = LIMIT
                    }
                    reasoning {
                        enableThinking = true
                        budgetTokens = BUDGET
                    }
                }
            log("think on, budget $BUDGET: $typed")
            assertEquals(Person("Bob", 30), (typed as? Typed.Value)?.value)
            assertTrue("it thought", !(typed as Typed.Value).reasoning.isNullOrBlank())
        }

    /**
     * Think on as an app gets it by default, with no thinking budget: a measurement for F151, since the host run with
     * no budget thought past its limit. The grammar must still never give JSON that doesn't decode.
     */
    @Test fun personWithThinkOnAndNoBudget() =
        runBlocking {
            val started = System.nanoTime()
            val typed =
                model.generate<Person>(PROMPT) {
                    sampling {
                        temperature = 0f
                        maxTokens = NO_BUDGET_LIMIT
                    }
                    reasoning { enableThinking = true }
                }
            val seconds = (System.nanoTime() - started) / 1e9
            log("think on, no budget (%.1f s, reasoning %d chars): $typed".format(seconds, typed.reasoningLength()))
            assertTrue("a Value or a reply cut at the limit, never Invalid: $typed", typed !is Typed.Invalid)
        }

    private fun Typed<*>.reasoningLength() = (this as? Typed.Value<*>)?.reasoning?.length ?: 0

    private companion object {
        const val CONTEXT = 4096
        const val BUDGET = 256
        const val LIMIT = 1024
        const val NO_BUDGET_LIMIT = 3072
        const val PROMPT = "A person named Bob aged 30."
    }
}
