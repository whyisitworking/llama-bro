package com.suhel.llamabro.demo.models

import com.suhel.llamabro.hub.HfModel

/** How the app names a model and its maker from what the Hub calls them (docs/design/models-page.md §3). */
object Names {
    /**
     * The one name a model has on every surface (D50): the catalog's name for any upload of its repo (the embedder's
     * too), else [display] of the repo's name.
     */
    fun of(key: HfModel): String =
        Catalog.byRepo(key.repo)?.realName
            ?: Catalog.embedder.realName.takeIf { key.repo.equals(Catalog.embedder.key.repo, ignoreCase = true) }
            ?: display(key.repo.substringAfter('/'))

    /**
     * `gemma-4-E4B-it-qat-q4_0-unquantized-GGUF` becomes `Gemma 4 E4B`: the upload's packaging words (GGUF, the
     * quantisation, QAT) and the instruction-tuning words (`it`, `Instruct`) dropped, separators to spaces, parameter
     * counts in capitals (`1b` as `1B`, `e2b` as `E2B`), the rest of the name's own casing kept.
     * Only the shown name: [key] keeps them, so an instruct upload and
     * its base stay two models.
     */
    fun display(repoName: String): String =
        repoName
            .replace(PACKAGING, " ")
            .replace(SEPARATORS, " ")
            .replace(INSTRUCTION, " ")
            .replace(PARAMETERS) { it.value.uppercase() }
            .replace(SPACES, " ")
            .trim(' ', '.')
            .replaceFirstChar { it.uppercaseChar() }

    /**
     * What identifies a model across its uploads: the name without packaging words, lower case, separators as the
     * name has them (`gemma-4-E4B-it-qat-q4_0-GGUF` and `gemma-4-E4B-it-GGUF` are one model). A suggestion's row and
     * its search row share it.
     */
    fun key(repoName: String): String =
        repoName
            .replace(PACKAGING_AND_LEAD, "")
            .trim('-', '_', '.', ' ')
            .lowercase()

    /**
     * The one maker map: the organisation's name for a known Hub owner, spelled as the catalog
     * spells its makers (so Qwen's own uploads and the catalog's Qwen read "Alibaba Qwen" alike), else the owner as the
     * Hub has it.
     */
    fun maker(owner: String): String =
        MAKERS.entries.firstOrNull { it.key.equals(owner, ignoreCase = true) }?.value ?: owner

    /**
     * Who made the model in [repo]: the catalog's maker for a repo it knows (it wins everywhere), else [original]'s
     * owner through [maker]; null when neither is known.
     */
    fun makerOf(repo: String, original: String?): String? = Catalog.byRepo(repo)?.maker ?: original?.let(::maker)

    // underscores belong to a quant's name (Q4_K_M), so the token is matched before separators are replaced
    private val PACKAGING =
        Regex(
            "(?<![A-Za-z0-9])(?:I?Q\\d+(?:_[A-Z0-9]+)*|BF16|F16|FP8|QAT|UNQUANTIZED|GGUF)(?![A-Za-z0-9])",
            RegexOption.IGNORE_CASE,
        )
    private val PACKAGING_AND_LEAD = Regex("[-_.\\s]*" + PACKAGING.pattern, RegexOption.IGNORE_CASE)
    private val SEPARATORS = Regex("[-_\\s]+")
    private val INSTRUCTION = Regex("(?<=^| )(?:it|instruct)(?= |$)", RegexOption.IGNORE_CASE)
    private val PARAMETERS = Regex("(?<=^| )[ae]?\\d+(?:\\.\\d+)?[bm](?= |$)", RegexOption.IGNORE_CASE)
    private val SPACES = Regex(" {2,}")
    private val MAKERS =
        mapOf(
            "google" to "Google",
            "Qwen" to "Alibaba Qwen",
            "LiquidAI" to "Liquid AI",
            "meta-llama" to "Meta",
            "microsoft" to "Microsoft",
            "mistralai" to "Mistral AI",
            "deepseek-ai" to "DeepSeek",
            "ibm-granite" to "IBM",
            "HuggingFaceTB" to "Hugging Face",
        )
}
