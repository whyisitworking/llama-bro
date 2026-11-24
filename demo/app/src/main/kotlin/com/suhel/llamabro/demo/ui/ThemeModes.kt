package com.suhel.llamabro.demo.ui

import com.suhel.llamabro.demo.data.settings.Theme
import com.suhel.llamabro.demo.designsystem.theme.ThemeMode

/** The stored choice as the design system draws it; each `when` fails to compile when an entry is added to one enum. */
fun Theme.mode(): ThemeMode =
    when (this) {
        Theme.System -> ThemeMode.System
        Theme.Light -> ThemeMode.Light
        Theme.Dark -> ThemeMode.Dark
    }

fun ThemeMode.theme(): Theme =
    when (this) {
        ThemeMode.System -> Theme.System
        ThemeMode.Light -> Theme.Light
        ThemeMode.Dark -> Theme.Dark
    }
