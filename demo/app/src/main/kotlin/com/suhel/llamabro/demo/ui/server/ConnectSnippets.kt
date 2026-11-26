package com.suhel.llamabro.demo.ui.server

import java.util.Locale

/** The tools the Server page shows how to connect, in its tabs' order (docs/design/server.md §3). */
enum class ConnectTool(val label: String) {
    Curl("curl"),
    OpenAiPython("OpenAI Python"),
    ClaudeCode("Claude Code"),
    LlamaChat("llama-chat"),
}

/** How [tool] reaches the server: [code] to paste into a terminal or a file. */
data class Snippet(val tool: ConnectTool, val code: String)

/**
 * The connect snippets with the server's URL and key filled in, and what the QR code holds. Each is the tool's own
 * plain form, so a person who knows it recognises it; the key goes where that tool takes it.
 */
object ConnectSnippets {
    /** One [Snippet] per [ConnectTool] for the server at [url] (`http://ip:port`), its [key] and the [model] served. */
    fun of(url: String, key: String, model: String): List<Snippet> =
        ConnectTool.entries.map { tool ->
            Snippet(
                tool,
                when (tool) {
                    ConnectTool.Curl -> curl(url, key)
                    ConnectTool.OpenAiPython -> openAiPython(url, key, model)
                    ConnectTool.ClaudeCode -> "ANTHROPIC_BASE_URL=$url ANTHROPIC_AUTH_TOKEN=$key claude"
                    ConnectTool.LlamaChat -> llamaChat(url, key)
                },
            )
        }

    private fun curl(url: String, key: String) =
        "curl $url/v1/chat/completions -H \"Authorization: Bearer $key\" -H \"Content-Type: application/json\" " +
            "-d '{\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}'"

    private fun openAiPython(url: String, key: String, model: String) =
        listOf(
            "from openai import OpenAI",
            "client = OpenAI(base_url=\"$url/v1\", api_key=\"$key\")",
            "print(client.chat.completions.create(model=\"$model\", " +
                "messages=[{\"role\": \"user\", \"content\": \"Hello\"}]).choices[0].message.content)",
        ).joinToString("\n")

    /**
     * llama-chat (upstream's web UI, `ui/apps/chat`) takes no server or key on its command line: its CLI only serves
     * the app on localhost, and the server and its "API Key" are entered in the app, so the snippet says where.
     */
    private fun llamaChat(url: String, key: String) =
        listOf(
            "npx llama-chat",
            "# then, in Llama Chat, connect to $url",
            "# with the API Key $key (Settings)",
        ).joinToString("\n")

    /**
     * What the QR code holds: `{"url":"http://ip:port","key":"…"}`. JSON rather than a URL with the key in its
     * fragment: no client reads either form yet, and a URL invites a phone's camera to open the server's root in a
     * browser, where there is no page to show and the key would stay in the browser's history. Two named fields say
     * what each part is, to a person reading the scan and to an app that later reads it.
     */
    fun qrPayload(url: String, key: String): String = "{\"url\":${json(url)},\"key\":${json(key)}}"

    private fun json(value: String): String =
        buildString {
            append('"')
            value.forEach { c ->
                when {
                    c == '"' || c == '\\' -> append('\\').append(c)
                    c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
}
