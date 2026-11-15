package com.suhel.llamabro.conversation

import com.suhel.llamabro.chat.Tool

/** Tools registered on a [Conversation.Builder]: sent with every request; those with a handler run automatically. */
internal class ToolRegistry {
    /** [requiresApproval]: a call waits for `approve` or `deny` before the handler runs (D62). */
    class Entry(val tool: Tool, val handler: (suspend (JsonArgs) -> String)?, val requiresApproval: Boolean)

    private val entries = linkedMapOf<String, Entry>()

    fun add(tool: Tool, handler: (suspend (JsonArgs) -> String)?, requiresApproval: Boolean) {
        require(tool.name !in entries) { "tool registered twice: ${tool.name}" }
        // a call to a tool without a handler already stops for the app, so approving it would mean nothing
        require(handler != null || !requiresApproval) { "tool ${tool.name} requires approval but has no handler" }
        entries[tool.name] = Entry(tool, handler, requiresApproval)
    }

    val tools: List<Tool> get() = entries.values.map { it.tool }

    /** The registered tool and handler, or null for a name the app never registered (an unknown tool, D62). */
    fun entry(name: String): Entry? = entries[name]
}
