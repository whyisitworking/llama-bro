package com.suhel.llamabro.sdk

import com.suhel.llamabro.sdk.model.Message

interface LlamaSession : AutoCloseable {

    fun prompt(message: Message)

    fun generate(): String?

    fun clear()
}
