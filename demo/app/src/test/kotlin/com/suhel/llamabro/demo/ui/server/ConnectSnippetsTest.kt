package com.suhel.llamabro.demo.ui.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connect snippets (docs/design/server.md §3): each tool's exact shape, with the URL and the key filled in. */
class ConnectSnippetsTest {
    private val url = "http://192.168.1.20:8080"
    private val key = "k3y_-AbC"
    private val model = "unsloth/Qwen3.5-4B-GGUF:Q4_K_M"
    private val snippets = ConnectSnippets.of(url, key, model).associate { it.tool to it.code }

    @Test fun oneSnippetPerToolInTheTabsOrder() {
        assertEquals(
            listOf(ConnectTool.Curl, ConnectTool.OpenAiPython, ConnectTool.ClaudeCode, ConnectTool.LlamaChat),
            ConnectSnippets.of(url, key, model).map { it.tool },
        )
        assertEquals(listOf("curl", "OpenAI Python", "Claude Code", "llama-chat"), ConnectTool.entries.map { it.label })
    }

    @Test fun curlPostsAChatToTheUrlWithTheKeyAsABearer() {
        assertEquals(
            "curl http://192.168.1.20:8080/v1/chat/completions -H \"Authorization: Bearer k3y_-AbC\" " +
                "-H \"Content-Type: application/json\" " +
                "-d '{\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}'",
            snippets.getValue(ConnectTool.Curl),
        )
    }

    @Test fun openAiPythonPointsTheClientAtV1WithTheKeyAndTheServedModel() {
        assertEquals(
            "from openai import OpenAI\n" +
                "client = OpenAI(base_url=\"http://192.168.1.20:8080/v1\", api_key=\"k3y_-AbC\")\n" +
                "print(client.chat.completions.create(model=\"unsloth/Qwen3.5-4B-GGUF:Q4_K_M\", " +
                "messages=[{\"role\": \"user\", \"content\": \"Hello\"}]).choices[0].message.content)",
            snippets.getValue(ConnectTool.OpenAiPython),
        )
    }

    @Test fun claudeCodeTakesTheBaseUrlAndTheKeyAsAnAuthToken() {
        assertEquals(
            "ANTHROPIC_BASE_URL=http://192.168.1.20:8080 ANTHROPIC_AUTH_TOKEN=k3y_-AbC claude",
            snippets.getValue(ConnectTool.ClaudeCode),
        )
    }

    @Test fun llamaChatRunsWithNpxAndSaysWhereTheUrlAndKeyGo() {
        val code = snippets.getValue(ConnectTool.LlamaChat)
        assertEquals("npx llama-chat", code.lines().first())
        // llama-chat has no flag for a server or a key: they are entered in the app, its key under "API Key"
        assertTrue(code, "http://192.168.1.20:8080" in code.lines().drop(1).joinToString("\n"))
        assertTrue(code, "API Key k3y_-AbC" in code)
    }

    @Test fun theKeyIsWhereverTheSnippetSaysAndNowhereElse() {
        val masked = ConnectSnippets.of(url, ServerFormat.MASK, model)
        masked.forEach { assertFalse(it.code, key in it.code) }
        assertEquals(1, Regex(Regex.escape(key)).findAll(snippets.getValue(ConnectTool.Curl)).count())
    }
}
