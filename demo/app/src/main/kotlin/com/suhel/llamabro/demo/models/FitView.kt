package com.suhel.llamabro.demo.models

/**
 * How a model runs on this phone, as a list or the sheet shows it: the [label] (the tint and the word) and the speed in
 * words per second.
 */
data class FitView(val label: FitLabel, val wordsPerSecond: Int)

object Fits {
    /**
     * The estimate everywhere (demo design §7.1, D101): [fileBytes] (the model and its encoder, what is downloaded and
     * later held in memory) against the memory budget, and the speed from [modelBytes] alone, since decoding reads the
     * weights.
     */
    fun quick(fileBytes: Long, modelBytes: Long, facts: DeviceFacts): FitView {
        val room = Fit.roomFromSize(fileBytes, facts.budgetBytes)
        val speed = Fit.tokensPerSecond(modelBytes, facts.chip)
        return FitView(Fit.label(room, speed), Fit.wordsPerSecond(speed))
    }

    /** A catalog entry's estimate, from the sizes it ships with. */
    fun quick(entry: CatalogEntry, facts: DeviceFacts): FitView =
        quick(entry.modelBytes + entry.mmprojBytes, entry.modelBytes, facts)
}
