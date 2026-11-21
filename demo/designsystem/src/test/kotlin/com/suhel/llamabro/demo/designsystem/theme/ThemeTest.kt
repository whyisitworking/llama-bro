package com.suhel.llamabro.demo.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Material's roles never fall back to Material's own values. */
class ThemeTest {
    @Test
    fun everyMaterialColourRoleIsAToken() {
        for (colors in listOf(Light, Dark)) {
            val tokens =
                with(colors) {
                    setOf(
                        canvas,
                        surface,
                        ink,
                        muted,
                        hairline,
                        accent,
                        onAccent,
                        accentSoft,
                        greatTint,
                        greatInk,
                        slowTint,
                        slowInk,
                        tooBigTint,
                        tooBigInk,
                        danger,
                        Color.Transparent,
                    )
                }
            val scheme = colors.toScheme()
            val roles =
                ColorScheme::class.java.methods.filter {
                    it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType &&
                        it.name.startsWith("get")
                }
            assertEquals("Material 3 1.4's 48 colour roles", 48, roles.size)
            for (role in roles) {
                val color = Color((role.invoke(scheme) as Long).toULong())
                assertTrue(
                    "${if (colors.isDark) "dark" else "light"} ${role.name} = $color is not a token",
                    color in tokens,
                )
            }
        }
    }

    @Test
    fun schemeKeepsReferenceCsNamedRoles() {
        for (c in listOf(Light, Dark)) {
            val s = c.toScheme()
            assertEquals(c.canvas, s.background)
            assertEquals(c.surface, s.surface)
            assertEquals(c.ink, s.onSurface)
            assertEquals(c.muted, s.onSurfaceVariant)
            assertEquals(c.hairline, s.outlineVariant)
            assertEquals(c.accent, s.primary)
            assertEquals(c.onAccent, s.onPrimary)
            assertEquals(c.accentSoft, s.primaryContainer)
            assertEquals(c.accent, s.onPrimaryContainer)
            assertEquals(c.accentSoft, s.secondaryContainer)
            assertEquals(Color.Transparent, s.surfaceTint)
        }
    }

    @Test
    fun aFailureIsInDangerNotTheSlowSand() {
        // R4: sand says "slow here", a fit; a failure that reaches a Material error container is the danger ink
        for (c in listOf(Light, Dark)) {
            val s = c.toScheme()
            assertEquals(c.danger, s.onErrorContainer)
            assertTrue(s.errorContainer != c.slowTint)
        }
    }

    @Test
    fun everyMaterialTextStyleIsOnTheDemoScale() {
        // sans only: the serif belongs to the two hero roles (D48), never to a Material widget
        val scale =
            with(DemoType) { setOf(screenTitle, title, titleSmall, body, bodyMedium, bodySmall, label, labelSmall) }
        // the public styles; Material 1.4's "Emphasized" variants are internal and derived from these
        val styles =
            Typography::class.java.methods.filter {
                it.parameterCount == 0 && it.returnType == TextStyle::class.java && '$' !in it.name
            }
        assertEquals("Material 3's 15 text styles", 15, styles.size)
        for (style in styles) {
            assertTrue("${style.name} is not a sans DemoType style", style.invoke(DemoTypography) in scale)
        }
    }

    @Test
    fun dialogsKeepTheirBottomCorners() {
        assertEquals(RoundedCornerShape(28.dp), DemoShapes.material.extraLarge)
        assertEquals(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), DemoShapes.sheet)
    }
}
