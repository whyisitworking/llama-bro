package com.suhel.llamabro.demo.ui.chat

import com.suhel.llamabro.demo.chat.Limits
import com.suhel.llamabro.demo.data.Source
import com.suhel.llamabro.demo.designsystem.components.ComposerState
import com.suhel.llamabro.demo.designsystem.components.ThinkLevel

/** Fixed values the previews of the chat screen share: one believable conversation, in each state a chat can be in. */
internal object ChatPreviewData {
    private const val BIO =
        "Here's a tighter version that leads with what you do:\n\n" +
            "**Maya Chen** designs calm, useful interfaces for small teams.\n\n" +
            "- Product design, five years\n- Prototypes in Compose\n\nWant it warmer or more formal?"
    private const val CODE =
        "A function that adds two numbers:\n\n```kotlin\nfun add(a: Int, b: Int) = a + b\n```\n\nCall it as `add(2, 3)`."

    fun user(
        id: Long,
        position: Int,
        text: String,
        time: String? = null,
        held: String? = null,
    ) = UserItem(id, position, text, images = emptyList(), time = time, heldNote = held)

    fun reply(
        id: Long,
        position: Int,
        text: String,
        time: String? = null,
        thoughtSeconds: Int? = null,
        reasoning: String? = null,
        speed: String? = "38 words/s",
        stopped: Boolean = false,
        failure: String? = null,
        cut: Boolean = false,
        canContinue: Boolean = false,
        sources: List<Source> = emptyList(),
    ) = ReplyItem(
        id,
        position,
        text,
        Markdown.parse(text),
        reasoning,
        thoughtSeconds,
        speed,
        time,
        stopped,
        failure,
        cut = cut,
        canContinue = canContinue,
        sources = sources,
    )

    val conversation: List<ChatItem> =
        listOf(
            user(1, 0, "Can you tighten my bio? It's a bit long."),
            reply(2, 1, BIO),
            user(3, 2, "Write me a function that adds two numbers"),
            reply(4, 3, CODE, reasoning = "Two ints, return the sum.", thoughtSeconds = 3),
        )

    private const val CARE =
        "# Care guide\n\n## Watering\n\nWater the fern when the top inch of soil is dry, about once a week. " +
            "Keep the soil moist but never soggy.\n\n## Light\n\nBright, indirect light; direct sun scorches its fronds."
    private val watering = CARE.indexOf("Water the fern")
    private val wateringEnd = CARE.indexOf("## Light") - 2

    /** A passage of the person's library a reply used, from a care guide they imported. */
    val source =
        Source(
            1,
            "care-guide",
            "Fern care guide",
            watering,
            wateringEnd,
            listOf("Care guide", "Watering"),
            CARE.substring(watering, wateringEnd),
        )
    private val light =
        CARE.indexOf("Bright").let { start ->
            Source(
                2,
                "care-guide",
                "Fern care guide",
                start,
                CARE.length,
                listOf("Care guide", "Light"),
                CARE.substring(start),
            )
        }

    /** A chat that answered from the library, its reply's sources under it. */
    val fromLibrary: List<ChatItem> =
        listOf(
            user(1, 0, "How often should I water my fern?"),
            reply(
                2,
                1,
                "About once a week, when the top inch of soil is dry [1]. Keep it out of direct sun [2].",
                sources = listOf(source, light),
            ),
        )

    /** The source's sheet, with the guide's text around the passage. */
    val passage = Passages.view(source, CARE)

    /** The same source once its document is removed from the library: the passage alone. */
    val passageAlone = Passages.view(source, null)

    /** The same, as the chat list layout draws it: a day label, and a time in each message's corner. */
    val bubbles: List<ChatItem> =
        listOf(DayItem("Today", 1)) +
            conversation.map {
                when (it) {
                    is UserItem -> it.copy(time = "9:38")
                    is ReplyItem -> it.copy(time = "9:38")
                    else -> it
                }
            }

    val withSwitch: List<ChatItem> = conversation + SwitchItem(5, "Switched to Qwen3.5 4B")

    val held: List<ChatItem> =
        listOf(user(1, 0, "Hello when you're ready", held = "Qwen3.5 0.8B will answer the moment it's here"))

    val failed: List<ChatItem> =
        listOf(
            user(1, 0, "Summarise this for me"),
            reply(2, 1, "", failure = "That didn't work.", speed = null),
        )

    val stopped: List<ChatItem> =
        listOf(
            user(1, 0, "Tell me a long story"),
            reply(2, 1, "Once upon a time, in a harbour town", stopped = true, speed = null),
        )

    /** A reply cut at its length limit (D60): the last one offers Continue, an earlier one is only marked. */
    val cut: List<ChatItem> =
        listOf(
            user(1, 0, "Write the whole harbour story"),
            reply(2, 1, "Once upon a time, in a harbour town, the boats came in at dusk", cut = true),
            user(3, 2, "Keep going"),
            reply(4, 3, "The lamps were lit one by one along the quay", cut = true, canContinue = true),
        )

    val streamingAnswer =
        streamingItem(
            "The harbour was quiet that morning, and the **gulls** had not yet",
            "Let me think about the setting.",
            2,
        )
    val streamingThinking =
        streamingItem(
            "",
            "The user wants a story. A harbour feels right: salt, rope, early light.",
            null,
            thinking = true,
        )

    private fun streamingItem(text: String, reasoning: String?, seconds: Int?, thinking: Boolean = false) =
        StreamingItem(9, text, Markdown.parse(text), reasoning, seconds, thinking)

    val composerIdle = ComposerState("", canAttach = true, think = null, busy = false)
    val composerThinking = ComposerState("", canAttach = true, think = ThinkLevel.Balanced, busy = false)
    val composerTyping = ComposerState("What about the soil?", canAttach = true, think = ThinkLevel.Off, busy = false)
    val composerBusy = ComposerState("", canAttach = true, think = ThinkLevel.Balanced, busy = true)

    val memoryLimit = Limits.memoryWarning("Qwen3.5 4B")
    val goneLimit = Limits.modelGone("Qwen3.5 0.8B")
    val contextLimit = Limits.contextExceeded("Gemma 3 4B")
    val visionLimit = Limits.noVision("Qwen3.5 0.8B", "Qwen3.5 4B")

    fun header(name: String, fraction: Float? = null, label: String? = null) = ChatHeader(name, fraction, label)
}
