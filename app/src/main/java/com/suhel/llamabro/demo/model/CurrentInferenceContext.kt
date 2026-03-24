package com.suhel.llamabro.demo.model

import com.suhel.llamabro.sdk.engine.LlamaEngine
import com.suhel.llamabro.sdk.model.ResourceState

data class CurrentInferenceContext(
    val model: Model,
    val engine: ResourceState<LlamaEngine>
)
