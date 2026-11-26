package com.suhel.llamabro.demo.models

import com.suhel.llamabro.demo.data.settings.CardSampling
import com.suhel.llamabro.demo.data.settings.Knob.MinP
import com.suhel.llamabro.demo.data.settings.Knob.PresencePenalty
import com.suhel.llamabro.demo.data.settings.Knob.Temperature
import com.suhel.llamabro.demo.data.settings.Knob.TopK
import com.suhel.llamabro.demo.data.settings.Knob.TopP
import com.suhel.llamabro.demo.data.settings.Knobs
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
    /** The original the upload was quantized from (the Hub's `base_model`): its page is the model's page. */
    val baseModel: String,
    val about: String,
    val thinks: Boolean,
    val sees: Boolean,
    /** The Hub's parameter count for the repo's GGUF (`gguf.total`, F175). */
    val parameters: Long,
    val modelBytes: Long,
    val mmprojBytes: Long,
    /**
     * The model card's recommended sampling (D60, F136): the GGUF uploads carry none (F17), so the catalog does, and a
     * model's settings start from it. Sources: each model's card, or for Gemma 3 the values Unsloth's guide gives from
     * the Gemma team, and for Llama 3.2 its `generation_config.json`. Qwen3.5's values for pictures are not used.
     */
    val sampling: CardSampling,
    /** It holds a spoken conversation (voice mode, D100): it hears and speaks. */
    val talks: Boolean = false,
)

/**
 * The embedding model the Library page indexes documents with (docs/design/rag.md): never a chat suggestion, since a
 * chat can't use it. Its size is pinned in `demo/catalog/pin.md` like the chat models'.
 */
data class EmbedderEntry(
    val key: HfModel,
    val realName: String,
    val maker: String,
    val baseModel: String,
    val modelBytes: Long,
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
            baseModel = "Qwen/Qwen3.5-0.8B",
            about =
                "Quick and light, it answers in a blink and can look at pictures. " +
                    "Long or tricky questions can trip it up.",
            thinks = true,
            sees = true,
            parameters = 752_393_024,
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
            baseModel = "google/gemma-3-4b-it-qat-q4_0-unquantized",
            about =
                "Writes with care and a good ear for tone, and can look at pictures. " +
                    "It is a little slower, and very long chats can use up its memory.",
            thinks = false,
            sees = true,
            parameters = 3_880_263_168,
            modelBytes = 2_489_894_016,
            mmprojBytes = 851_251_456,
            sampling = CardSampling(Knobs.of(Temperature to 1f, TopP to 0.95f, TopK to 64f, MinP to 0f)),
        )
    private val deep =
        CatalogEntry(
            key = HfModel.parse("unsloth/Qwen3.5-4B-GGUF:Q4_K_M"),
            realName = "Qwen3.5 4B",
            maker = "Alibaba Qwen",
            baseModel = "Qwen/Qwen3.5-4B",
            about =
                "Works a problem through step by step before it answers, and can look at pictures. " +
                    "It takes longer to start replying.",
            thinks = true,
            sees = true,
            parameters = 4_205_751_296,
            modelBytes = 2_740_937_888,
            mmprojBytes = 675_569_344,
            sampling = CardSampling(thinking = qwenThinking, answering = qwenAnswering),
        )
    private val llama =
        CatalogEntry(
            key = HfModel.parse("unsloth/Llama-3.2-3B-Instruct-GGUF:Q4_K_M"),
            realName = "Llama 3.2 3B",
            maker = "Meta",
            baseModel = "meta-llama/Llama-3.2-3B-Instruct",
            about =
                "A friendly all-rounder for chat, writing and quick help. " +
                    "It can't look at pictures and doesn't think out loud.",
            thinks = false,
            sees = false,
            parameters = 3_212_749_888,
            modelBytes = 2_019_377_600,
            mmprojBytes = 0,
            sampling = CardSampling(Knobs.of(Temperature to 0.6f, TopP to 0.9f)),
        )
    private val nine =
        CatalogEntry(
            key = HfModel.parse("unsloth/Qwen3.5-9B-GGUF:Q4_K_M"),
            realName = "Qwen3.5 9B",
            maker = "Alibaba Qwen",
            baseModel = "Qwen/Qwen3.5-9B",
            about =
                "Gives the most thoughtful answers of the suggestions, and can look at pictures. " +
                    "It is heavy for this phone, so it replies slowly and keeps less of a long chat in mind.",
            thinks = true,
            sees = true,
            parameters = 8_953_803_264,
            modelBytes = 5_680_522_464,
            mmprojBytes = 921_705_024,
            sampling = CardSampling(thinking = qwenThinking, answering = qwenAnswering),
        )

    private val omni =
        CatalogEntry(
            key = HfModel.parse("ggml-org/LFM3-Omni-2B-GGUF:Q4_K_M"),
            realName = "LFM3 Omni 2B",
            maker = "Liquid AI",
            baseModel = "LiquidAI/LFM3-Omni-2B",
            about =
                "Listens and answers out loud, with captions, and can chat by text too. " +
                    "It is small, so its answers are short and simple.",
            thinks = false,
            sees = false,
            parameters = 1_728_801_600,
            modelBytes = 1_099_630_048,
            mmprojBytes = 506_842_432,
            // the card decodes text greedily (`generation_config.json`: temperature 0, top_k 1); its speech sampling
            // (audio_temperature 0.4) is the server's, not a chat's
            sampling = CardSampling(Knobs.of(Temperature to 0f, TopK to 1f)),
            talks = true,
        )

    /**
     * The Library's embedder: bge-small-en-v1.5, English, 384 dimensions, the model F191 measured retrieval with, in
     * the llama.cpp team's Q8_0 upload. No reranker is suggested: on that set it found no more answers (F191).
     */
    val embedder =
        EmbedderEntry(
            key = HfModel.parse("ggml-org/bge-small-en-v1.5-Q8_0-GGUF:Q8_0"),
            realName = "BGE Small",
            maker = "BAAI",
            baseModel = "BAAI/bge-small-en-v1.5",
            modelBytes = 36_685_152,
        )

    /** First run's three picks. */
    val picks: List<CatalogEntry> = listOf(swift, writer, deep)

    /** "Suggested for this phone", in order (demo design §4.3). */
    val suggested: List<CatalogEntry> = listOf(swift, writer, deep, llama, nine, omni)

    fun byKey(key: HfModel): CatalogEntry? = suggested.firstOrNull { it.key == key }

    /** The entry for any upload of this repo, whatever the tag. */
    fun byRepo(repo: String): CatalogEntry? = suggested.firstOrNull { it.key.repo.equals(repo, ignoreCase = true) }
}
