package com.suhel.llamabro.demo.models

/**
 * How a model runs on this phone, as a list or the sheet shows it: the [label] (the tint and the word) and the speed in
 * words per second. [real] is true when the SDK's projection backs it, false for the quick estimate from file sizes.
 */
data class FitView(val label: FitLabel, val wordsPerSecond: Int, val real: Boolean)

object Fits {
    /**
     * The quick estimate (demo design §7.1): [fileBytes] (the model and its image reader, what is downloaded and later
     * held
     * in memory) against the memory budget, and the speed from [modelBytes] alone, since decoding reads the weights.
     */
    fun quick(fileBytes: Long, modelBytes: Long, facts: DeviceFacts): FitView {
        val room = Fit.roomFromSize(fileBytes, facts.budgetBytes)
        val speed = Fit.tokensPerSecond(modelBytes, facts.chip)
        return FitView(Fit.label(room, speed), Fit.wordsPerSecond(speed), real = false)
    }

    /** The real one: the SDK's projection says how much context fits, and for which chip. */
    fun real(e: HubEstimate): FitView {
        val room = Fit.roomFromEstimate(e.fits, e.contextTokens)
        val speed = Fit.tokensPerSecond(e.modelBytes, e.chip)
        return FitView(Fit.label(room, speed), Fit.wordsPerSecond(speed), real = true)
    }

    /** A catalog entry's quick estimate, from the sizes it ships with. */
    fun quick(entry: CatalogEntry, facts: DeviceFacts): FitView =
        quick(entry.modelBytes + entry.mmprojBytes, entry.modelBytes, facts)
}
