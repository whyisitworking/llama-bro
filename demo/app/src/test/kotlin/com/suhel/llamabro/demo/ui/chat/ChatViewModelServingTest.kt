package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.demo.chat.LimitAction
import com.suhel.llamabro.demo.chat.Limits
import com.suhel.llamabro.demo.eventually
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A chat while server mode serves a model (docs/design/server.md §3): a turn on another model is held with a line whose
 * Stop serving ends serving and asks again, and the switcher asks before it moves the chat off the served model.
 */
class ChatViewModelServingTest : ChatViewModelFixture() {
    @Test fun theServingLineOffersStopServingWhichAsksTheQuestionAgain() =
        runBlocking {
            testHub.install(plain)
            served.value = plain
            reply("hello from the chat's own model")
            val vm = viewModel()
            vm.onText("hi")
            vm.send()
            eventually("the serving line") { vm.limit.value?.second == LimitAction.StopServing }
            val line = checkNotNull(vm.limit.value)
            assertEquals(Limits.serving("Llama 3.2 3B", "Qwen3.5 0.8B"), line)
            assertEquals("Stop serving", line.secondLabel)

            vm.serving.stopAndRetry()
            eventually("the reply") {
                vm.rows.value.any { it is ReplyItem && it.text == "hello from the chat's own model" }
            }
            assertEquals(1, serverStops.get())
            assertNull(served.value)
        }

    @Test fun aSwitchOffTheServedModelAsksFirstAndStopServingSwitches() =
        runBlocking {
            testHub.install(plain)
            models.changed()
            val vm = viewModel()
            eventually("the chat's model") { vm.handle.current.value == swift }
            // nothing served: the switch goes ahead without a question
            assertNull(vm.serving.switchStopsServing(plain))

            served.value = swift
            assertEquals("Qwen3.5 0.8B", vm.serving.switchStopsServing(plain))
            assertEquals(
                "Stop serving Qwen3.5 0.8B to use Llama 3.2 3B?",
                stopServingTitle("Qwen3.5 0.8B", "Llama 3.2 3B"),
            )
            // the served model itself is shared, not replaced: no question
            assertNull(vm.serving.switchStopsServing(swift))

            vm.serving.stopAndSwitch(plain)
            eventually("the switch") { vm.handle.current.value == plain }
            assertEquals(1, serverStops.get())
        }
}
