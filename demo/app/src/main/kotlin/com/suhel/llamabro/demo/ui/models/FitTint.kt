package com.suhel.llamabro.demo.ui.models

import com.suhel.llamabro.demo.designsystem.components.CardTint
import com.suhel.llamabro.demo.models.FitLabel

/** The word a fit label shows and the tint its card and text share (pd-design 5.2). */
fun FitLabel.text(): String =
    when (this) {
        FitLabel.RunsGreat -> "Runs great"
        FitLabel.RunsWell -> "Runs well"
        FitLabel.SlowHere -> "Slow here"
        FitLabel.TooBig -> "Too big"
    }

fun FitLabel.tint(): CardTint =
    when (this) {
        FitLabel.RunsGreat -> CardTint.Great
        FitLabel.RunsWell -> CardTint.Plain
        FitLabel.SlowHere -> CardTint.Slow
        FitLabel.TooBig -> CardTint.TooBig
    }
