package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.designsystem.components.FitLevel
import com.suhel.llamabro.demo.models.FitLabel

/** The word a fit label shows (demo design §8.6). */
fun FitLabel.text(): String =
    when (this) {
        FitLabel.RunsGreat -> "Runs great"
        FitLabel.RunsWell -> "Runs well"
        FitLabel.SlowHere -> "Slow here"
        FitLabel.TooBig -> "Too big"
    }

/** The pace figure a row shows for this label. */
fun FitLabel.level(): FitLevel =
    when (this) {
        FitLabel.RunsGreat -> FitLevel.Great
        FitLabel.RunsWell -> FitLevel.Well
        FitLabel.SlowHere -> FitLevel.Slow
        FitLabel.TooBig -> FitLevel.TooBig
    }
