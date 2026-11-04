package com.suhel.llamabro

/** Scripted llama-server chat streams for Conversation tests (shapes from server-task.cpp, see the fixtures). */
internal object Replies {
    private fun chunk(id: String, delta: String, finish: String? = null, extra: String = ""): String =
        "data: {\"choices\":[{\"finish_reason\":${finish?.let { "\"$it\"" } ?: "null"},\"index\":0,\"delta\":$delta}]," +
            "\"id\":\"$id\",\"object\":\"chat.completion.chunk\"$extra}\n\n"

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    fun timings(promptN: Int, cacheN: Int, predictedN: Int) =
        ",\"timings\":{\"cache_n\":$cacheN,\"prompt_n\":$promptN,\"prompt_per_second\":100.0,\"predicted_n\":$predictedN,\"predicted_per_second\":10.0}"

    /** A complete text reply, optionally with reasoning first. */
    fun text(content: String, reasoning: String? = null, id: String = "chatcmpl-1", finish: String = "stop", timings: String = timings(10, 0, 3)): List<String> {
        val out = mutableListOf(chunk(id, "{\"role\":\"assistant\",\"content\":null}"))
        reasoning?.let { out += chunk(id, "{\"reasoning_content\":${q(it)}}") }
        content.chunked(4).forEach { out += chunk(id, "{\"content\":${q(it)}}") }
        out += chunk(id, "{}", finish = finish, extra = timings)
        out += "data: [DONE]\n\n"
        return out
    }

    /** A reply that calls tools: (id, name, arguments). */
    fun toolCalls(vararg calls: Triple<String, String, String>, id: String = "chatcmpl-1", content: String = ""): List<String> {
        val out = mutableListOf(chunk(id, "{\"role\":\"assistant\",\"content\":null}"))
        if (content.isNotEmpty()) out += chunk(id, "{\"content\":${q(content)}}")
        calls.forEachIndexed { i, (callId, name, args) ->
            out += chunk(id, "{\"tool_calls\":[{\"index\":$i,\"id\":${q(callId)},\"type\":\"function\",\"function\":{\"name\":${q(name)},\"arguments\":\"\"}}]}")
            args.chunked(5).forEach { out += chunk(id, "{\"tool_calls\":[{\"index\":$i,\"function\":{\"arguments\":${q(it)}}}]}") }
        }
        out += chunk(id, "{}", finish = "tool_calls", extra = timings(10, 0, 3))
        out += "data: [DONE]\n\n"
        return out
    }

    fun exceed(prompt: Int, ctx: Int) =
        """{"error":{"code":400,"message":"request ($prompt tokens) exceeds the available context size ($ctx tokens)","type":"exceed_context_size_error","n_prompt_tokens":$prompt,"n_ctx":$ctx}}"""
}

/** Scripts a FakeModel: each chat request takes the next reply; other endpoints get "{}". */
internal fun FakeModel.script(vararg replies: List<String>) {
    val queue = ArrayDeque(replies.toList())
    onRequest = { r ->
        if (r.endpoint == "chat") {
            val reply = queue.removeFirstOrNull() ?: error("no scripted reply for request ${requests.size}")
            reply.forEach { r.chunk(it) }
            r.done()
        } else {
            r.chunk("{}")
            r.done()
        }
    }
}

internal val FakeModel.chatBodies: List<String> get() = synchronized(requests) { requests.filter { it.endpoint == "chat" }.map { it.body } }
