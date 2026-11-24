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
 * Material 3's scheme derived from the tokens. Every role is mapped, so no stock Material colour can
 * reach a screen through a Material widget; the tonal elevation tint is switched off. The roles the design doesn't
 * name follow one rule: secondary and tertiary track the accent (their "on" roles the onAccent), surface containers
 * are the surface, an error is the danger ink on the plain surface (never the "slow here" sand, which is a fit: models
 * audit R4), the inverse roles swap ink and canvas, and the scrim is the darkest token.
 */
internal fun DemoColors.toScheme(): ColorScheme =
    ColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        inversePrimary = accentSoft,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentSoft,
        onSecondaryContainer = accent,
        tertiary = accent,
        onTertiary = onAccent,
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
        errorContainer = surface,
        onErrorContainer = danger,
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

/** The sheet scrim of the design: 30% in light, 50% in dark. */
internal val DemoColors.scrim: Color get() = scrimBase.copy(alpha = if (isDark) 0.5f else 0.3f)

/**
 * A fill one step off the canvas for code blocks and open thoughts (the design's `--code` and `--think`):
 * the canvas moved 3.5% (light) or 2% (dark) towards the ink.
 */
internal val DemoColors.subtleFill: Color get() = lerp(canvas, ink, if (isDark) 0.02f else 0.035f)

/** A control drawn on a photo (the ✕ on an attached picture): a dark scrim and a white glyph read on any picture. */
internal val DemoColors.photoScrim: Color get() = Color.Black.copy(alpha = 0.6f)
internal val DemoColors.onPhotoScrim: Color get() = Color.White

/**
 * Behind a photo shown full-screen: black in both themes, so the photo reads as it is and the chat doesn't show through
 * (at 95% it did, in light most of all); it fades only while a pull closes the viewer.
 */
internal val DemoColors.photoBackdrop: Color get() = Color.Black

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
    val colors = if (dark) DarkDemoColors else LightDemoColors
    CompositionLocalProvider(LocalDemoColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toScheme(),
            typography = DemoTypography,
            shapes = DemoShapes.material,
            content = content,
        )
    }
}

/** The opaque end of an alpha mask (a scroll's edge fade): never a colour shown. */
internal val FadeMask: Color = Color.Black

/**
 * A download ring's track: at least 1.5:1 against the surface and the canvas in both themes, so a frozen arc's ring
 * still reads as a ring on the dark sheet; the soft accent is 1.2:1 there.
 */
internal val DemoColors.ringTrack: Color get() = if (isDark) Color(0xFF3D3D3D) else Color(0xFFBEBEBE)
