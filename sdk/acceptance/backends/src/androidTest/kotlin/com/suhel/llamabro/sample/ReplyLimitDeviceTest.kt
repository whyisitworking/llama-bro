package com.suhel.llamabro.sample

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suhel.llamabro.ContextSize
import com.suhel.llamabro.LlamaBro
import com.suhel.llamabro.Model
import com.suhel.llamabro.ModelSource
import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The reply limits the demo relies on (D60), on the phone with a small thinking model:
 * - the reasoning budget ends the thinking and the model then answers, inside the reply's length limit;
 * - a reply cut at its length limit, in its answer or in its thinking, is continued by sending it back as the last
 *   message (llama-server's `prefill_assistant`), and the model goes on from where it stopped.
 * Runs only when asked, with the model's file name in the app's files, for example:
 *   ANDROID_SERIAL=<serial> ./gradlew -p sdk :acceptance:backends:connectedFullDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.suhel.llamabro.sample.ReplyLimitDeviceTest \
 *     -Pandroid.testInstrumentationRunnerArguments.replyLimit=Qwen3.5-0.8B-Q4_K_M.gguf
 * Replies go to logcat under "ReplyLimit" and to filesDir/replylimit.log.
 */
@RunWith(AndroidJUnit4::class)
class ReplyLimitDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var model: Model

    @Before fun load() =
        runBlocking {
            val name = InstrumentationRegistry.getArguments().getString("replyLimit")
            assumeTrue("a device check: pass the runner argument replyLimit=<file.gguf>", name != null)
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
        Log.i("ReplyLimit", line)
        File(context.filesDir, "replylimit.log").appendText(line + "\n")
    }

    private class Reply(val reasoning: String, val content: String, val reason: FinishReason?)

    private suspend fun collect(label: String, flow: Flow<ChatEvent>): Reply {
        val events = flow.toList()
        val r =
            Reply(
                events.filterIsInstance<ChatEvent.ReasoningDelta>().joinToString("") { it.delta },
                events.filterIsInstance<ChatEvent.ContentDelta>().joinToString("") { it.delta },
                events.filterIsInstance<ChatEvent.Done>().lastOrNull()?.reason,
            )
        val t = events.filterIsInstance<ChatEvent.Done>().lastOrNull()?.timings
        log("$label: ${r.reason}, ${t?.predictedTokens} tokens, thought ${r.reasoning.length} chars")
        log("$label thinking: ${r.reasoning.takeLast(LOG_CHARS).replace('\n', ' ')}")
        log("$label answer: ${r.content.take(LOG_CHARS).replace('\n', ' ')}")
        return r
    }

    @Test fun theReasoningBudgetEndsTheThinkingAndTheModelAnswers() =
        runBlocking {
            val convo = model.conversation()
            val reply =
                collect(
                    "budget $BUDGET",
                    convo.send(HARD_QUESTION) {
                        sampling {
                            temperature = 0f
                            maxTokens = LIMIT
                        }
                        reasoning {
                            enableThinking = true
                            budgetTokens = BUDGET
                        }
                    },
                )
            assertTrue("it thought", reply.reasoning.isNotBlank())
            assertTrue("it answered after the budget", reply.content.isNotBlank())
            assertEquals("the answer ended by itself, inside the limit", FinishReason.Stop, reply.reason)
        }

    @Test fun aReplyCutInItsAnswerIsContinuedFromWhereItStopped() =
        runBlocking {
            val first =
                collect(
                    "answer, cut",
                    model.conversation().send(COUNT) {
                        sampling {
                            temperature = 0f
                            maxTokens = CUT_AT
                        }
                        reasoning { enableThinking = false }
                    },
                )
            assertEquals(FinishReason.Length, first.reason)
            val rest =
                collect(
                    "answer, continued",
                    model
                        .conversation(null, listOf(Message.user(COUNT), Message.Assistant(content = first.content)))
                        .resume {
                            sampling {
                                temperature = 0f
                                maxTokens = LIMIT
                            }
                            reasoning { enableThinking = false }
                        },
                )
            val whole = first.content + rest.content
            log("answer, whole: ${whole.replace('\n', ' ')}")
            // it goes on counting: the whole reply reaches 30 and never starts again from 1
            assertTrue("reaches 30", whole.contains("30"))
            assertFalse("does not start over", rest.content.trimStart().startsWith("1,"))
            assertEquals(1, Regex("""\b1,""").findAll(whole).count())
        }

    @Test fun aReplyCutInItsThinkingIsContinuedAndThenAnswers() =
        runBlocking {
            val first =
                collect(
                    "thinking, cut",
                    model.conversation().send(HARD_QUESTION) {
                        sampling {
                            temperature = 0f
                            maxTokens = CUT_AT
                        }
                        reasoning { enableThinking = true }
                    },
                )
            assertEquals(FinishReason.Length, first.reason)
            assertTrue("cut while thinking", first.content.isEmpty() && first.reasoning.isNotEmpty())
            val rest =
                collect(
                    "thinking, continued",
                    model
                        .conversation(
                            null,
                            listOf(
                                Message.user(HARD_QUESTION),
                                Message.Assistant(content = "", reasoning = first.reasoning),
                            ),
                        ).resume {
                            sampling {
                                temperature = 0f
                                maxTokens = LIMIT
                            }
                            reasoning {
                                enableThinking = true
                                budgetTokens = BUDGET
                            }
                        },
                )
            assertTrue("it answered after continuing its thought", rest.content.isNotBlank())
        }

    private companion object {
        const val CONTEXT = 4096
        const val BUDGET = 128
        const val LIMIT = 1024
        const val CUT_AT = 24
        const val LOG_CHARS = 400
        const val HARD_QUESTION = "How many R letters are in the word strawberry? Think it through."
        const val COUNT = "Count from 1 to 30, separated by commas. Write only the numbers."
    }
}
