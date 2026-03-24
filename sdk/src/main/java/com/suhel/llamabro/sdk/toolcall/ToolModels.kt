package com.suhel.llamabro.sdk.toolcall

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: ToolParameters = ToolParameters()
)

@Serializable
data class ToolParameters(
    val properties: Map<String, ToolParameter> = emptyMap(),
    val required: List<String> = emptyList(),
)

@Serializable
data class ToolParameter(
    val type: Type,
    val description: String? = null,
    val properties: Map<String, ToolParameter> = emptyMap(),
    val items: ToolParameter? = null,
    val required: List<String> = emptyList(),
    val enum: List<String> = emptyList(),
    val nullable: Boolean = false,
)

enum class Type {
    STRING, NUMBER, INTEGER, BOOLEAN, OBJECT, ARRAY,
}

@Serializable
data class ToolCall(
    val id: String? = null,
    val name: String,
    val arguments: JsonObject,
)

@Serializable
data class ToolResult(
    val id: String? = null,
    val name: String,
    val result: JsonElement,
)
