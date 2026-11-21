package com.suhel.llamabro.demo.models

/**
 * A model's fit before its files are listed (demo design, "A row's fit, from the search list alone"): the size of
 * its Recommended file guessed from the Hub's parameter count, so a search row is labelled with no extra request.
 */
object Guess {
    /**
     * Bits a weight takes in a Q4_K_M file, the mean over the catalog's six Q4_K_M models (F175). Q4_K_M mixes
     * 4-bit and 6-bit blocks and keeps some tensors wider, so it is above 4.
     */
    private const val BITS_PER_WEIGHT = 5.2

    /** The image reader's file as a share of the model's, the mean over the catalog's four that see images (F175). */
    private const val READER_SHARE = 0.285

    private const val BITS_PER_BYTE = 8

    /** A mixture-of-experts name carries its active parameters: `A3B` is three billion. */
    private val ACTIVE = Regex("(?<![A-Za-z0-9])A(\\d+(?:\\.\\d+)?)B(?![A-Za-z])")

    private const val BILLION = 1_000_000_000.0

    /** A parameter count in a model's name: `7B`, `0.5B`; not `A3B` (active) or `E4B` (effective). */
    private val NAMED = Regex("(?<![A-Za-z0-9.])(\\d+(?:\\.\\d+)?)B(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)

    /**
     * The parameter count a model's [name] carries ("Qwen2.5 Coder 7B" is seven billion), for a Hub row whose GGUF
     * header gave none: its size and fit are guessed from it rather than left blank.
     */
    fun namedParameters(name: String): Long? =
        NAMED
            .find(name)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
            ?.let { (it * BILLION).toLong() }

    /** The size of the Recommended (Q4_K_M) file of a model with [parameters] weights. */
    fun recommendedBytes(parameters: Long): Long = (parameters * BITS_PER_WEIGHT / BITS_PER_BYTE).toLong()

    /** The size of the image reader that comes with a model of [parameters] weights that sees images. */
    fun readerBytes(parameters: Long): Long = (recommendedBytes(parameters) * READER_SHARE).toLong()

    /** The parameters a token uses: from the `A<n>B` in a mixture-of-experts model's [name]; null for any other. */
    fun activeParameters(name: String, architecture: String): Long? {
        if ("moe" !in architecture.lowercase()) return null
        val billions =
            ACTIVE
                .find(name)
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull() ?: return null
        return (billions * BILLION).toLong()
    }

    /**
     * The share of a file's weights a token reads: a mixture-of-experts model's active parameters over all of them,
     * both from its [name] when it says them (`Qwen3-30B-A3B` reads a tenth; all from [parameters], the Hub's count,
     * when the name has only the active one), else the Hub's [activeParameters] over [parameters]; 1 for any other
     * model. One function for every place a fit is figured (the row's guess, the card's sizes, a file on the phone,
     * the switcher), so one file has one fit; the name comes first because a file on the phone has only its name.
     */
    fun activeShare(name: String, parameters: Long? = null, activeParameters: Long? = null): Double {
        val named =
            ACTIVE
                .find(name)
                ?.groupValues
                ?.get(1)
                ?.toDoubleOrNull()
                ?.let { it * BILLION }
        val active = named ?: activeParameters?.toDouble() ?: return 1.0
        val all =
            (if (named != null) namedParameters(name) else null)?.toDouble() ?: parameters?.toDouble() ?: return 1.0
        return if (all <= 0 || active >= all) 1.0 else active / all
    }

    /**
     * How [model] runs on [facts]: memory from every parameter (and the reader, when it sees), speed from the ones a
     * token uses. Null when the Hub gave no parameter count.
     */
    fun fit(model: SearchModel, facts: DeviceFacts): FitView? =
        fit(model.parameters, model.activeParameters, model.sees, facts)

    /**
     * The fewest parameters a model worth searching for has (D128): the smallest chat models in GGUF are about 135
     * million (SmolLM2 135M). Under a "Fits this phone" cap below this, every search would say "No models match".
     */
    const val SMALLEST_MODEL = 100_000_000L

    /**
     * The largest parameter count whose Recommended file isn't Too big on [facts] (F176): the cap the search sends for
     * "Fits this phone". Found with [fit] itself, for a model that reads no images, so the filter and a row's fit
     * symbol can't disagree on a text model; one that sees images needs room for its reader too, and near the cap it
     * may show as Too big rather than be hidden.
     */
    fun fitCap(facts: DeviceFacts): Long {
        val fits = { parameters: Long -> fit(parameters, null, false, facts)?.label != FitLabel.TooBig }
        // the file grows with the count, so the counts that fit are one run from zero: search for its end
        var low = 0L
        var high = (facts.budgetBytes.coerceAtLeast(1) * BITS_PER_BYTE / BITS_PER_WEIGHT).toLong() * 2
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (fits(middle)) low = middle else high = middle - 1
        }
        return low
    }

    /** As [fit] of a search result, from its facts alone: a row carries them, not the result. */
    fun fit(parameters: Long?, activeParameters: Long?, sees: Boolean, facts: DeviceFacts): FitView? {
        if (parameters == null) return null
        val reader = if (sees) readerBytes(parameters) else 0L
        val speedBytes = recommendedBytes(activeParameters ?: parameters)
        return Fits.quick(recommendedBytes(parameters) + reader, speedBytes, facts)
    }
}
