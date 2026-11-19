package com.suhel.llamabro.chat

/** A chat message. */
public sealed interface Message {
    /** Write it as `Message.System`: imported bare, `System` hides `java.lang.System`. */
    public data class System(public val text: String) : Message

    /** Text and images from the user. */
    public data class User(public val content: List<Content>) : Message

    /** A reply: its text, its reasoning and the tools it called. */
    public data class Assistant(
        public val content: String?,
        public val reasoning: String? = null,
        public val toolCalls: List<ToolCall> = emptyList(),
    ) : Message

    /** A tool's result for the call [toolCallId]. */
    public data class ToolResult(public val toolCallId: String, public val content: String) : Message

    /**
     * A compaction node, appended to `Conversation.messages` the way tools/ui stores it: the
     * summary of everything before it except the last [keptSteps] steps, which the model still reads as written.
     * Never sent as-is: the model sees the summary framed as a user message, then the kept steps, then what
     * follows the node.
     */
    public data class Compaction(public val summary: String, public val keptSteps: Int = 0) : Message

    public companion object {
        public fun system(text: String): Message = System(text)

        public fun user(text: String): Message = User(listOf(Content.Text(text)))

        public fun user(vararg content: Content): Message = User(content.toList())
    }
}
