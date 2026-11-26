package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.demo.chat.ChatEngine
import com.suhel.llamabro.demo.chat.ChatServing
import com.suhel.llamabro.demo.models.Names
import com.suhel.llamabro.hub.HfModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A chat's way out of server mode (docs/design/server.md §3): the switcher asks before it moves the chat off the
 * served model, and the serving line's Stop serving ends serving and asks the chat's question again. The stops run
 * on [background], the app's scope, so the switch or the retry after them is never cut off by the screen leaving.
 */
class ChatServingActions(
    private val mode: ChatServing,
    private val handle: ChatEngine.ChatHandle,
    private val retry: () -> Unit,
    private val background: CoroutineScope,
) {
    /**
     * The name of the model being served when a switch to [to] must stop serving it first, so the switcher asks
     * ("Stop serving <served> to use <other>?"); null when the switch can go ahead.
     */
    fun switchStopsServing(to: HfModel): String? = mode.served()?.takeIf { mode.blocksSwitch(to) }?.let(Names::of)

    /** The switcher's question answered Stop serving: serving ends, then the chat moves to [to]. */
    fun stopAndSwitch(to: HfModel) {
        background.launch {
            mode.stop()
            handle.switchModel(to)
        }
    }

    /** The serving line's Stop serving: serving ends, then the question nobody answered is asked again. */
    fun stopAndRetry() {
        background.launch {
            mode.stop()
            retry()
        }
    }
}
