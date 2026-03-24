package com.suhel.llamabro.sdk.toolcall

import com.suhel.llamabro.sdk.chat.pipeline.ToolCallMarker

data class ToolCallDefinition(
    val marker: ToolCallMarker,
    val callParser: (String) -> ToolCall,
    val callSerializer: (ToolCall) -> String,
    val definitionFormatter: (List<ToolDefinition>) -> String
)
