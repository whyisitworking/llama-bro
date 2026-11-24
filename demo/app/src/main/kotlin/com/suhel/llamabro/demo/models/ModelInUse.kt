package com.suhel.llamabro.demo.models

import com.suhel.llamabro.hub.HfModel

/**
 * Whatever keeps a model in memory, as the library needs it: told to let a model go before its file is removed. The
 * chat package's model host implements it, so the library doesn't depend on the chat.
 */
fun interface ModelInUse {
    suspend fun close(key: HfModel)
}
