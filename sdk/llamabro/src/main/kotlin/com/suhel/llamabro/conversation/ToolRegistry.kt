package com.suhel.llamabro.conversation

import com.suhel.llamabro.chat.Tool

/** Tools registered on a [Conversation.Builder]: sent with every request; those with a handler run automatically. */
internal class ToolRegistry {
    class Entry(val tool: Tool, val handler: (suspend (JsonArgs) -> String)?)

    private val entries = linkedMapOf<String, Entry>()

    fun add(tool: Tool, handler: (suspend (JsonArgs) -> String)?) {
        require(tool.name !in entries) { "tool registered twice: ${tool.name}" }
        entries[tool.name] = Entry(tool, handler)
    }

    val tools: List<Tool> get() = entries.values.map { it.tool }

    fun handler(name: String): (suspend (JsonArgs) -> String)? = entries[name]?.handler
}
