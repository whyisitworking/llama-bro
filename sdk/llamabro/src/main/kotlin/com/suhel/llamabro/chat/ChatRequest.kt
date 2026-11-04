package com.suhel.llamabro.chat

/**
 * An OpenAI-shaped chat request, sent to llama-server's `/v1/chat/completions` handler. Unset (null)
 * fields are left out, so the GGUF's and llama-server's defaults apply (D18).
 *
 * @property extraJson a JSON object merged into the body last: any llama-server field, overriding typed ones.
 */
public data class ChatRequest(
    public val messages: List<Message>,
    public val tools: List<Tool> = emptyList(),
    public val toolChoice: ToolChoice? = null,
    public val parallelToolCalls: Boolean? = null,
    public val responseFormat: ResponseFormat? = null,
    public val sampling: Sampling = Sampling(),
    public val reasoning: Reasoning = Reasoning(),
    public val stop: List<String> = emptyList(),
    public val extraJson: String? = null,
)
