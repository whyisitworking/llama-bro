package com.suhel.llamabro.chat

/** A function the model may call. [parametersSchema] is a JSON Schema object, as a string. */
public data class Tool(
    public val name: String,
    public val description: String,
    public val parametersSchema: String,
) {
    public companion object {
        public fun function(name: String, description: String, parametersSchema: String): Tool =
            Tool(name, description, parametersSchema)
    }
}

public sealed interface ToolChoice {
    public data object Auto : ToolChoice
    public data object None : ToolChoice
    public data object Required : ToolChoice
    public data class Function(public val name: String) : ToolChoice
}

/** A tool call made by the model. [argumentsJson] is the raw arguments string, which may not be valid JSON. */
public data class ToolCall(public val id: String, public val name: String, public val argumentsJson: String)
