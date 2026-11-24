package com.suhel.llamabro.demo.ui

import com.suhel.llamabro.demo.data.settings.Theme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode

/** The stored choice as the design system draws it; the two enums list the same entries. */
fun Theme.mode(): ThemeMode = ThemeMode.valueOf(name)

fun ThemeMode.theme(): Theme = Theme.valueOf(name)
