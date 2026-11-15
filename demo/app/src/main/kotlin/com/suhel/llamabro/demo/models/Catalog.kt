package com.suhel.llamabro.demo.models

import com.suhel.llamabro.demo.chat.CardSampling
import com.suhel.llamabro.demo.chat.Knob.MinP
import com.suhel.llamabro.demo.chat.Knob.PresencePenalty
import com.suhel.llamabro.demo.chat.Knob.Temperature
import com.suhel.llamabro.demo.chat.Knob.TopK
import com.suhel.llamabro.demo.chat.Knob.TopP
import com.suhel.llamabro.demo.chat.Knobs
import com.suhel.llamabro.hub.HfModel

/**
 * A model the app knows by name. Friendly names belong to exactly these files (demo design §3). Sizes are pinned from
 * the repo trees read on 2026-11-10 (commits and file names in `demo/catalog/pin.md`); a mismatch at runtime
 * (the hub's plan disagrees) only refreshes the size, never the name.
 */
data class CatalogEntry(
    val key: HfModel,
    val realName: String,
    val maker: String,
    val line: String,
    val about: String,
    val thinks: Boolean,
    val sees: Boolean,
    val modelBytes: Long,
    val mmprojBytes: Long,
    /**
     * The model card's recommended sampling (D60, F136): the GGUF uploads carry none (F17), so the catalog does, and a
     * model's settings start from it. Sources: each model's card, or for Gemma 3 the values Unsloth's guide gives from
     * the Gemma team, and for Llama 3.2 its `generation_config.json`. Qwen3.5's values for pictures are not used.
     */
    val sampling: CardSampling,
)

object Catalog {
    /** Qwen3.5's card, thinking (every size). */
    private val qwenThinking =
        Knobs.of(Temperature to 1f, TopP to 0.95f, TopK to 20f, MinP to 0f, PresencePenalty to 1.5f)

    /** Qwen3.5 4B's and 9B's card, not thinking (the 0.8B's differs). */
    private val qwenAnswering =
        Knobs.of(Temperature to 0.7f, TopP to 0.8f, TopK to 20f, MinP to 0f, PresencePenalty to 1.5f)

    private val swift =
        CatalogEntry(
            key = HfModel.parse("unsloth/Qwen3.5-0.8B-GGUF:Q4_K_M"),
            realName = "Qwen3.5 0.8B",
            maker = "Alibaba Qwen",
            line = "Tiny and quick, for everyday questions",
            about =
                "Quick and light, it answers in a blink and can look at pictures. " +
                    "Long or tricky questions can trip it up.",
            thinks = true,
            sees = true,
            modelBytes = 532_517_120,
            mmprojBytes = 207_346_528,
            sampling =
                CardSampling(
                    thinking = qwenThinking,
                    answering = Knobs.of(Temperature to 1f, TopP to 1f, TopK to 20f, MinP to 0f, PresencePenalty to 2f),
                ),
        )
    private val writer =
        CatalogEntry(
            key = HfModel.parse("unsloth/gemma-3-4b-it-qat-GGUF:Q4_K_M"),
            realName = "Gemma 3 4B",
            maker = "Google",
            line = "A careful writer, good with tone",
            about =
                "Writes with care and a good ear for tone, and can look at pictures. " +
                    "It is a little slower, and very long chats can use up its memory.",
            thinks = false,
            sees = true,
            modelBytes = 2_489_894_016,
            mmprojBytes = 851_251_456,
            sampling = CardSampling(Knobs.of(Temperature to 1f, TopP to 0.95f, TopK to 64f, MinP to 0f)),
        )
    private val deep =
        CatalogEntry(
            key = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"),
            realName = "Qwen3.5 4B",
            maker = "Alibaba Qwen",
            line = "Thinks things through before answering",
            about =
                "Works a problem through step by step before it answers, and can look at pictures. " +
                    "It takes longer to start replying.",
            thinks = true,
            sees = true,
            modelBytes = 2_740_937_888,
            mmprojBytes = 675_569_344,
            sampling = CardSampling(thinking = qwenThinking, answering = qwenAnswering),
        )
    private val llama =
        CatalogEntry(
            key = HfModel.parse("unsloth/Llama-3.2-3B-Instruct-GGUF:Q4_K_M"),
            realName = "Llama 3.2 3B",
            maker = "Meta",
            line = "Meta's all-rounder, friendly and fast",
            about =
                "A friendly all-rounder for chat, writing and quick help. " +
                    "It can't look at pictures and doesn't think out loud.",
            thinks = false,
            sees = false,
            modelBytes = 2_019_377_600,
            mmprojBytes = 0,
            sampling = CardSampling(Knobs.of(Temperature to 0.6f, TopP to 0.9f)),
        )
    private val nine =
        CatalogEntry(
            key = HfModel.parse("unsloth/Qwen3.5-9B-GGUF:Q4_K_M"),
            realName = "Qwen3.5 9B",
            maker = "Alibaba Qwen",
            line = "Smarter, but heavy for this phone",
            about =
                "Gives the most thoughtful answers of the five, and can look at pictures. " +
                    "It is heavy for this phone, so it replies slowly and keeps less of a long chat in mind.",
            thinks = true,
            sees = true,
            modelBytes = 5_680_522_464,
            mmprojBytes = 921_705_024,
            sampling = CardSampling(thinking = qwenThinking, answering = qwenAnswering),
        )

    /** First run's three picks. */
    val picks: List<CatalogEntry> = listOf(swift, writer, deep)

    /** "Suggested for this phone", in order (demo design §4.3). */
    val suggested: List<CatalogEntry> = listOf(swift, writer, deep, llama, nine)

    fun byKey(key: HfModel): CatalogEntry? = suggested.firstOrNull { it.key == key }

    /**
     * The name to show for [key]: the model's own name from the catalog, else the repo's.
     */
    fun nameOf(key: HfModel): String = byRepo(key.repo)?.realName ?: key.repo.substringAfter('/').removeSuffix("-GGUF")

    /** The entry for any upload of this repo, whatever the tag. */
    fun byRepo(repo: String): CatalogEntry? = suggested.firstOrNull { it.key.repo.equals(repo, ignoreCase = true) }
}
