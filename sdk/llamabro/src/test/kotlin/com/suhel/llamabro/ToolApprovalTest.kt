package com.suhel.llamabro

import com.suhel.llamabro.chat.ChatEvent
import com.suhel.llamabro.chat.FinishReason
import com.suhel.llamabro.chat.Message
import com.suhel.llamabro.conversation.Conversation
import com.suhel.llamabro.conversation.LbToolAbort
import com.suhel.llamabro.conversation.addToolResult
import com.suhel.llamabro.conversation.canCompact
import com.suhel.llamabro.conversation.compact
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** D62's approval: a call to a tool registered with `requiresApproval` waits for `approve` or `deny`. */
class ToolApprovalTest {
    private suspend fun setup(): Pair<FakeEngine, Model> {
        val engine = FakeEngine()
        return engine to testLlamaBro(engine).loadModel(ModelSource.file("/m.gguf"))
    }

    private fun messagesOf(body: String): List<String> =
        ((Json.parseToJsonElement(body) as JsonObject)["messages"] as JsonArray).map { it.toString() }

    private fun call(id: String, name: String = id) = Triple(id, name, "{}")

    private fun toolMessage(id: String, content: String) =
        """{"role":"tool","tool_call_id":"$id","content":"$content"}"""

    /** a (automatic), b (needs approval), c (automatic), each logging its run. */
    private fun Conversation.Builder.abc(log: MutableList<String>) {
        tool("a", "a", "{}") {
            log += "a"
            "A"
        }
        tool("b", "b", "{}", requiresApproval = true) {
            log += "b"
            "B"
        }
        tool("c", "c", "{}") {
            log += "c"
            "C"
        }
    }

    private fun announced(events: List<ChatEvent>) =
        events.filterIsInstance<ChatEvent.ToolCallReceived>().map { it.call.id }

    @Test
    fun approvalStopsTheBatch() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("a"), call("b"), call("c")))
            val log = mutableListOf<String>()
            val chat = model.conversation { abc(log) }
            val events = chat.send("go").toList()
            assertEquals(listOf("a"), log)
            assertEquals(listOf("a", "b"), announced(events))
            assertEquals(FinishReason.ToolCallsPending, (events.last() as ChatEvent.Done).reason)
            assertEquals(1, engine.model.chatBodies.size)
            assertEquals("b", chat.pendingApproval.value?.id)
        }

    @Test
    fun approveRunsTheCallAndTheRestThenContinues() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("a"), call("b"), call("c")), Replies.text("done"))
            val log = mutableListOf<String>()
            val chat = model.conversation { abc(log) }
            chat.send("go").toList()
            val events = chat.approve("b").toList()
            assertEquals(listOf("a", "b", "c"), log)
            assertEquals(listOf("c"), announced(events))
            assertEquals(2, engine.model.chatBodies.size)
            assertEquals(
                listOf(toolMessage("a", "A"), toolMessage("b", "B"), toolMessage("c", "C")),
                messagesOf(engine.model.chatBodies[1]).takeLast(3),
            )
            assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
            assertNull(chat.pendingApproval.value)
        }

    @Test
    fun denySendsAnErrorAndContinues() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("a"), call("b"), call("c")), Replies.text("ok"))
            val log = mutableListOf<String>()
            val chat = model.conversation { abc(log) }
            chat.send("go").toList()
            val events = chat.deny("b").toList()
            assertEquals(listOf("a", "c"), log)
            assertEquals(ChatEvent.ToolResult("b", "b", "Error: denied by the user"), events.first())
            assertEquals(
                listOf(toolMessage("a", "A"), toolMessage("b", "Error: denied by the user"), toolMessage("c", "C")),
                messagesOf(engine.model.chatBodies[1]).takeLast(3),
            )
            assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
        }

    @Test
    fun denyWithAReasonSendsTheReason() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("b")), Replies.text("ok"))
            val log = mutableListOf<String>()
            val chat = model.conversation { abc(log) }
            chat.send("go").toList()
            chat.deny("b", "not now").toList()
            assertEquals(emptyList<String>(), log)
            assertEquals(toolMessage("b", "Error: not now"), messagesOf(engine.model.chatBodies[1]).last())
        }

    @Test
    fun twoApprovalsStopTwice() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("b1", "b"), call("b2", "b")), Replies.text("ok"))
            val log = mutableListOf<String>()
            val chat = model.conversation { abc(log) }
            chat.send("go").toList()
            assertEquals("b1", chat.pendingApproval.value?.id)
            val first = chat.approve("b1").toList()
            assertEquals(FinishReason.ToolCallsPending, (first.last() as ChatEvent.Done).reason)
            assertEquals(listOf("b2"), announced(first))
            assertEquals("b2", chat.pendingApproval.value?.id)
            assertEquals(1, engine.model.chatBodies.size)
            val second = chat.approve("b2").toList()
            assertEquals(FinishReason.Stop, (second.last() as ChatEvent.Done).reason)
            assertEquals(listOf("b", "b"), log)
        }

    @Test
    fun approvingACallThatIsNotPendingFails() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("a"), call("b")))
            val chat = model.conversation { abc(mutableListOf()) }
            chat.send("go").toList()
            val flow = chat.approve("a") // creating the flow never throws
            val e = runCatching { flow.toList() }.exceptionOrNull()
            assertTrue("$e", e is IllegalArgumentException)
            assertEquals("b", chat.pendingApproval.value?.id)
            assertTrue(runCatching { chat.deny("zzz").toList() }.exceptionOrNull() is IllegalArgumentException)
        }

    @Test
    fun pendingApprovalSurvivesRestore() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("a"), call("b"), call("c")), Replies.text("done"))
            val log = mutableListOf<String>()
            val json =
                model
                    .conversation { abc(log) }
                    .apply { send("go").toList() }
                    .toJson()
            val restored = model.restoreConversation(json) { abc(log) }
            assertEquals("b", restored.pendingApproval.value?.id)
            val events = restored.approve("b").toList()
            assertEquals(listOf("a", "b", "c"), log)
            assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
            assertEquals(
                listOf(toolMessage("a", "A"), toolMessage("b", "B"), toolMessage("c", "C")),
                messagesOf(engine.model.chatBodies[1]).takeLast(3),
            )
        }

    @Test
    fun resumeWhileACallWaitsStopsAgain() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("a"), call("b"), call("c")))
            val log = mutableListOf<String>()
            val chat = model.conversation { abc(log) }
            chat.send("go").toList()
            val events = chat.resume().toList()
            assertEquals(ChatEvent.Done(FinishReason.ToolCallsPending, null, null), events.last())
            assertEquals(1, engine.model.chatBodies.size)
            assertEquals(listOf("a"), log)
            assertEquals("b", chat.pendingApproval.value?.id)
        }

    @Test
    fun approvalNeedsAHandler() =
        runTest {
            val (_, model) = setup()
            val e = runCatching { model.conversation { tool("b", "b", "{}", requiresApproval = true) } }
            assertTrue("${e.exceptionOrNull()}", e.exceptionOrNull() is IllegalArgumentException)
        }

    @Test
    fun manualCallBeforeTheApprovalStaysPending() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("m"), call("b"), call("c")), Replies.text("ok"))
            val log = mutableListOf<String>()
            val chat =
                model.conversation {
                    tool("m", "manual", "{}")
                    abc(log)
                }
            chat.send("go").toList()
            assertEquals("b", chat.pendingApproval.value?.id)
            val events = chat.approve("b").toList()
            assertEquals(listOf("b", "c"), log)
            assertEquals(FinishReason.ToolCallsPending, (events.last() as ChatEvent.Done).reason)
            assertEquals(1, engine.model.chatBodies.size)
            assertNull(chat.pendingApproval.value)
            chat.addToolResult("m", "M")
            val resumed = chat.resume().toList()
            assertEquals(FinishReason.Stop, (resumed.last() as ChatEvent.Done).reason)
            assertEquals(
                listOf(toolMessage("b", "B"), toolMessage("c", "C"), toolMessage("m", "M")),
                messagesOf(engine.model.chatBodies[1]).takeLast(3),
            )
        }

    @Test
    fun addToolResultRecomputesPendingApproval() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("m"), call("b")))
            val chat =
                model.conversation {
                    tool("m", "manual", "{}")
                    abc(mutableListOf())
                }
            chat.send("go").toList()
            assertEquals("b", chat.pendingApproval.value?.id)
            chat.addToolResult("b", "answered by the app")
            assertNull(chat.pendingApproval.value)
        }

    @Test
    fun resumeAfterAnAbortRerunsTheAbortedCall() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("a"), call("b"), call("c")), Replies.text("done"))
            val log = mutableListOf<String>()
            var aborts = 1
            val chat =
                model.conversation {
                    tool("a", "a", "{}") {
                        log += "a"
                        "A"
                    }
                    tool("b", "b", "{}") {
                        log += "b"
                        if (aborts-- > 0) throw LbToolAbort("offline")
                        "B"
                    }
                    tool("c", "c", "{}") {
                        log += "c"
                        "C"
                    }
                }
            val first = chat.send("go").toList()
            assertEquals(FinishReason.ToolAborted, (first.last() as ChatEvent.Done).reason)
            assertEquals(
                listOf("a"),
                chat.messages.value
                    .filterIsInstance<Message.ToolResult>()
                    .map { it.toolCallId },
            )
            assertNull(chat.pendingApproval.value)
            val events = chat.resume().toList()
            assertEquals(listOf("a", "b", "b", "c"), log)
            assertEquals(listOf("b", "c"), announced(events))
            assertEquals(2, engine.model.chatBodies.size)
            assertEquals(
                listOf(toolMessage("a", "A"), toolMessage("b", "B"), toolMessage("c", "C")),
                messagesOf(engine.model.chatBodies[1]).takeLast(3),
            )
            assertEquals(FinishReason.Stop, (events.last() as ChatEvent.Done).reason)
        }

    @Test
    fun approveAndDenyTakeRequestOptions() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(
                Replies.toolCalls(call("b1", "b")),
                Replies.toolCalls(call("b2", "b")),
                Replies.text("ok"),
            )
            val chat = model.conversation { abc(mutableListOf()) }
            chat.send("go").toList()
            chat.approve("b1") { sampling { temperature = 0.25f } }.toList()
            chat.deny("b2", "no") { sampling { temperature = 0.5f } }.toList()
            val (_, afterApprove, afterDeny) =
                engine.model.chatBodies.map { Json.parseToJsonElement(it) as JsonObject }
            assertEquals("0.25", afterApprove["temperature"].toString())
            assertEquals("0.5", afterDeny["temperature"].toString())
        }

    @Test
    fun approvedCallThatAbortsWaitsForApprovalAgain() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("a"), call("b")))
            val abort = LbToolAbort("offline")
            val chat =
                model.conversation {
                    tool("a", "a", "{}") { "A" }
                    tool("b", "b", "{}", requiresApproval = true) { throw abort }
                }
            chat.send("go").toList()
            val done = chat.approve("b").toList().last() as ChatEvent.Done
            assertEquals(FinishReason.ToolAborted, done.reason)
            assertSame(abort, done.abort)
            assertEquals("b", chat.pendingApproval.value?.id)
            val resumed = chat.resume().toList()
            assertEquals(FinishReason.ToolCallsPending, (resumed.last() as ChatEvent.Done).reason)
            assertEquals(1, engine.model.chatBodies.size)
        }

    @Test
    fun approveAndDenyFailWhileBusy() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("b")), Replies.text("ok"))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val chat =
                model.conversation {
                    tool("b", "b", "{}", requiresApproval = true) {
                        entered.complete(Unit)
                        release.await()
                        "B"
                    }
                }
            chat.send("go").toList()
            val job = launch { chat.approve("b").collect() }
            entered.await()
            assertNull(chat.pendingApproval.value)
            val approveError = runCatching { chat.approve("b").toList() }.exceptionOrNull()
            val denyError = runCatching { chat.deny("b").toList() }.exceptionOrNull()
            assertTrue("$approveError", approveError is LlamaBroException.Busy)
            assertTrue("$denyError", denyError is LlamaBroException.Busy)
            release.complete(Unit)
            job.join()
            assertEquals(2, engine.model.chatBodies.size)
        }

    @Test
    fun compactionWaitsForUnansweredCalls() =
        runTest {
            val (engine, model) = setup()
            engine.model.script(Replies.toolCalls(call("a"), call("b"), call("c")), Replies.text("done"))
            val chat = model.conversation { abc(mutableListOf()) }
            chat.send("go").toList()
            val before = chat.messages.value
            assertFalse(chat.canCompact(preservedSteps = 0))
            val e = runCatching { chat.compact(preservedSteps = 0).toList() }.exceptionOrNull()
            assertTrue("$e", e is IllegalStateException && e.message!!.contains("[b, c]"))
            assertEquals(before, chat.messages.value)
            assertEquals("b", chat.pendingApproval.value?.id)
            assertEquals(1, engine.model.chatBodies.size)
            chat.deny("b").toList()
            assertTrue(chat.canCompact(preservedSteps = 0))
        }
}
