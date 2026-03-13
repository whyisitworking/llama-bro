package com.suhel.llamabro.sdk.model

data class PromptFormat(
    val systemPrefix: String,
    val systemSuffix: String,
    val userPrefix: String,
    val userSuffix: String,
    val assistantPrefix: String,
    val assistantSuffix: String,
)
