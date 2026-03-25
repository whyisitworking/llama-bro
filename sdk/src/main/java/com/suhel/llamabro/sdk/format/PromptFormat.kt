package com.suhel.llamabro.sdk.format

data class PromptFormat(
    val systemPrefix: String,
    val userPrefix: String,
    val assistantPrefix: String,
    val endOfTurn: String,
    val stopStrings: List<String> = emptyList(),
)
