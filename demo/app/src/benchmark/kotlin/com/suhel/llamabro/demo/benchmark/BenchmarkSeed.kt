package com.suhel.llamabro.demo.benchmark

import android.content.Context
import com.suhel.llamabro.demo.AppContainer
import com.suhel.llamabro.demo.data.AppLayout
import com.suhel.llamabro.demo.data.MessageKind
import com.suhel.llamabro.demo.data.NewMessage
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * The data every benchmark starts from: 300 chats for the Chat list, one chat of 300 messages with code blocks, the
 * Chat list layout, a model on the phone. Written once; a marker file tells the benchmarks it is there. Every start
 * restores the big chat to 300 messages.
 */
object BenchmarkSeed {
    const val READY_MARKER = "benchmark-seed-ready"
    const val BIG_CHAT_TITLE = "Big chat of 300 messages"
    private const val CHATS = 300
    private const val BIG_CHAT_MESSAGES = 300
    private const val MODEL = "unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"

    suspend fun seedOnce(context: Context, container: AppContainer) {
        val marker = File(context.filesDir, READY_MARKER)
        if (marker.exists()) {
            restoreTheBigChat(container)
            return
        }
        container.settings.markFirstRunSeen()
        container.settings.setLayout(AppLayout.ChatList)
        container.settings.setLastModel(MODEL)
        if (container.chats
                .chats()
                .first()
                .isEmpty()
        ) {
            for (n in 1..CHATS) {
                val id = container.chats.createChat(MODEL)
                container.chats.append(id, NewMessage(MessageKind.User, "Question number $n about the weather"))
                container.chats.append(id, NewMessage(MessageKind.Assistant, replyText(40), modelKey = MODEL))
            }
            val big = container.chats.createChat(MODEL)
            for (n in 1..BIG_CHAT_MESSAGES / 2) {
                container.chats.append(big, NewMessage(MessageKind.User, "Show me example $n of a loop"))
                container.chats.append(big, NewMessage(MessageKind.Assistant, replyText(160), modelKey = MODEL))
            }
            container.chats.rename(big, BIG_CHAT_TITLE)
        }
        marker.writeText("ready")
    }

    /**
     * A benchmark that streams a reply leaves it in the chat, and the chat grows by one reply per run. Cutting it back
     * to 300 messages at every start keeps each run measuring the chat the budgets are written for.
     */
    private suspend fun restoreTheBigChat(container: AppContainer) {
        val big =
            container.chats
                .chats()
                .first()
                .firstOrNull { it.title == BIG_CHAT_TITLE }
                ?: return
        container.chats.truncateFrom(big.id, BIG_CHAT_MESSAGES)
    }

    /** About [words] words of markdown: a sentence, a list, a code block, in that order, repeated. */
    fun replyText(words: Int): String {
        val block =
            "Here is how it works, in plain words. First, the idea. Then the steps:\n\n" +
                "- the first step\n- the second step\n- the third step\n\n" +
                "```kotlin\nfun main() {\n    for (i in 1..3) println(\"step \$i\")\n}\n```\n\n" +
                "And that is all there is to it, nothing more to add. "
        val one = block.split(' ').size
        return buildString { repeat(words / one + 1) { append(block) } }.split(' ').take(words).joinToString(" ")
    }
}
