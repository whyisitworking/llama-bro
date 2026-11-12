package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

enum class ThemeMode { System, Light, Dark }

/**
 * Material 3's scheme derived from the tokens (Reference C). Every role is mapped, so no stock Material colour can
 * reach a screen through a Material widget; the tonal elevation tint is switched off. The roles Reference C doesn't
 * name are the builder's call, settled here (PD T3): secondary and tertiary follow the accent, surface containers
 * are the surface, errors use the "slow here" sand (a limit is stated plainly, not alarmed), the inverse roles swap
 * ink and canvas, and the scrim is the darkest token.
 */
internal fun DemoColors.toScheme(): ColorScheme =
    ColorScheme(
        primary = accent,
        onPrimary = canvas,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        inversePrimary = accentSoft,
        secondary = accent,
        onSecondary = canvas,
        secondaryContainer = accentSoft,
        onSecondaryContainer = accent,
        tertiary = accent,
        onTertiary = canvas,
        tertiaryContainer = accentSoft,
        onTertiaryContainer = accent,
        background = canvas,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = canvas,
        onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        inverseSurface = ink,
        inverseOnSurface = canvas,
        error = danger,
        onError = surface,
        errorContainer = slowTint,
        onErrorContainer = slowInk,
        outline = muted,
        outlineVariant = hairline,
        scrim = scrimBase,
        surfaceBright = surface,
        surfaceDim = canvas,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
        surfaceContainerLow = surface,
        surfaceContainerLowest = surface,
        primaryFixed = accentSoft,
        primaryFixedDim = accentSoft,
        onPrimaryFixed = accent,
        onPrimaryFixedVariant = accent,
        secondaryFixed = accentSoft,
        secondaryFixedDim = accentSoft,
        onSecondaryFixed = accent,
        onSecondaryFixedVariant = accent,
        tertiaryFixed = accentSoft,
        tertiaryFixedDim = accentSoft,
        onTertiaryFixed = accent,
        onTertiaryFixedVariant = accent,
    )

/** The darkest token of the set: ink in light, canvas in dark. */
internal val DemoColors.scrimBase: Color get() = if (isDark) canvas else ink

/** The sheet scrim of the mockups: 30% in light, 50% in dark. */
internal val DemoColors.scrim: Color get() = scrimBase.copy(alpha = if (isDark) 0.5f else 0.3f)

/**
 * A fill one step off the canvas for code blocks and open thoughts (the mockups' `--code` and `--think`):
 * the canvas moved 3.5% (light) or 2% (dark) towards the ink. Builder's call, settled here (PD T3).
 */
internal val DemoColors.subtleFill: Color get() = lerp(canvas, ink, if (isDark) 0.02f else 0.035f)

/**
 * Every Material text style mapped onto the [DemoType] roles, all sans: the serif is only for the two hero roles
 * (`displayHero`, `sheetTitle`), which the app's own components use, so a Material widget (a dialog's title) never
 * turns serif.
 */
internal val DemoTypography =
    Typography(
        displayLarge = DemoType.screenTitle,
        displayMedium = DemoType.screenTitle,
        displaySmall = DemoType.screenTitle,
        headlineLarge = DemoType.screenTitle,
        headlineMedium = DemoType.screenTitle,
        headlineSmall = DemoType.screenTitle,
        titleLarge = DemoType.screenTitle,
        titleMedium = DemoType.title,
        titleSmall = DemoType.titleSmall,
        bodyLarge = DemoType.body,
        bodyMedium = DemoType.bodyMedium,
        bodySmall = DemoType.bodySmall,
        labelLarge = DemoType.bodyMedium,
        labelMedium = DemoType.labelSmall,
        labelSmall = DemoType.labelSmall,
    )

@Composable
fun DemoTheme(themeMode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark =
        when (themeMode) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
    val colors = if (dark) Dark else Light
    CompositionLocalProvider(LocalDemoColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toScheme(),
            typography = DemoTypography,
            shapes = DemoShapes.material,
            content = content,
        )
    }
}
