package com.suhel.llamabro.sdk.config

import com.suhel.llamabro.sdk.chat.pipeline.TagDelimiter
import com.suhel.llamabro.sdk.toolcall.ToolCall
import com.suhel.llamabro.sdk.toolcall.ToolDefinition

data class ToolCallCapability(
    val tags: TagDelimiter,
    val callParser: (String) -> ToolCall,
    val callSerializer: (ToolCall) -> String,
    val resultSerializer: (com.suhel.llamabro.sdk.toolcall.ToolResult) -> String = { it.result.toString() },
    val definitionFormatter: (List<ToolDefinition>) -> String,
)
