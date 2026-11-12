package com.suhel.llamabro.chat

/** A function the model may call. [parametersSchema] is a JSON Schema object, as a string. */
public data class Tool(public val name: String, public val description: String, public val parametersSchema: String) {
    public companion object {
        public fun function(name: String, description: String, parametersSchema: String): Tool =
            Tool(name, description, parametersSchema)
    }
}

/** OpenAI's `tool_choice`. */
public sealed interface ToolChoice {
    /** The model decides. */
    public data object Auto : ToolChoice

    /** No tool calls. */
    public data object None : ToolChoice

    /** At least one tool call. */
    public data object Required : ToolChoice

    /** Call this tool. */
    public data class Function(public val name: String) : ToolChoice
}

/** A tool call made by the model. [argumentsJson] is the raw arguments string, which may not be valid JSON. */
public data class ToolCall(public val id: String, public val name: String, public val argumentsJson: String)
