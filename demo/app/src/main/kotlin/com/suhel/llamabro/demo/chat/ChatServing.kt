package com.suhel.llamabro.demo.chat

import com.suhel.llamabro.hub.HfModel

/**
 * Server mode as a chat sees it (docs/design/server.md §3): the model [served] to the Wi-Fi, whether a switch to
 * another model must first stop serving ([blocksSwitch]), and stopping it ([stop]). A chat on the served model shares
 * it; one that would move to another asks first, since the switch waits for serving to end.
 */
class ChatServing(val served: () -> HfModel?, val blocksSwitch: (HfModel) -> Boolean, val stop: suspend () -> Unit) {
    companion object {
        /** No server mode: nothing is served, nothing blocks. */
        val None = ChatServing({ null }, { false }, {})
    }
}
