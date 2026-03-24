package com.suhel.llamabro.demo.model

import com.suhel.llamabro.sdk.config.ModelProfile

data class Model(
    val id: String,
    val name: String,
    val description: String? = null,
    val downloadUrl: String,
    val profile: ModelProfile,
)
