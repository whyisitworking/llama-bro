package com.suhel.llamabro.demo.model

import com.suhel.llamabro.sdk.LlamaEngine

data class CurrentModel(
    val model: Model,
    val engine: LlamaEngine
)
