package com.najdev.snapvault.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contrast is the one property of a palette that is objectively checkable, and the one most
 * likely to be wrong: a colour pair chosen by eye in dark mode routinely fails in light, and
 * nothing in the build notices.
 *
 * These compute WCAG 2.1 relative luminance and assert the ratios the UI actually depends on.
 * The numbers are printed on every run so a palette change shows its effect rather than just
 * passing or failing.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeContrastTest {

    @Test
    fun onPrimaryIsReadableOnPrimaryInBothThemes() {
        assertPair("dark", SnapVaultColorScheme.onPrimary, SnapVaultColorScheme.primary, "onPrimary/primary")
        assertPair("light", SnapVaultLightColorScheme.onPrimary, SnapVaultLightColorScheme.primary, "onPrimary/primary")
    }

    // SnapVaultColors resolves against LocalThemeIsDark, so these are read out of a real
    // composition rather than restated as literals — a test that repeats the hex values
    // would keep passing after someone changed them.
    @Test
    fun warningForegroundIsReadableOnTheWarningFill() {
        listOf("dark" to true, "light" to false).forEach { (name, dark) ->
            // Color is an inline value class, so it cannot be lateinit — nullable instead.
            var warning: Color? = null
            var onWarning: Color? = null
            runComposeUiTest {
                setContent {
                    SnapVaultTheme(darkMode = dark) {
                        warning = SnapVaultColors.warning
                        onWarning = SnapVaultColors.onWarning
                    }
                }
            }
            assertPair(name, requireNotNull(onWarning), requireNotNull(warning), "onWarning/warning")
        }
    }

    @Test
    fun bodyTextIsReadableOnItsSurfaceInBothThemes() {
        listOf("dark" to SnapVaultColorScheme, "light" to SnapVaultLightColorScheme).forEach { (name, scheme) ->
            assertPair(name, scheme.onSurface, scheme.surface, "onSurface/surface")
            assertPair(name, scheme.onBackground, scheme.background, "onBackground/background")
            assertPair(name, scheme.onError, scheme.error, "onError/error")
        }
    }

    // The log panel is fixed in both themes, so it only needs checking once — but it is the
    // pair that was actually broken before round 1, which is why it is pinned here.
    @Test
    fun logForegroundIsReadableOnTheTerminalSurface() {
        assertPair("log", LogColors.onSurface, LogColors.surface, "onSurface/surface")
        listOf(
            "success" to LogColors.success,
            "warning" to LogColors.warning,
            "info" to LogColors.info,
            "error" to LogColors.error,
            "muted" to LogColors.muted,
            "prompt" to LogColors.prompt,
        ).forEach { (label, tag) ->
            // Tags are short bold labels, so the large-text threshold applies.
            assertPair("log", tag, LogColors.surface, "$label/surface", minimum = LARGE_TEXT_MINIMUM)
        }
    }

    private fun assertPair(
        theme: String,
        foreground: Color,
        background: Color,
        label: String,
        minimum: Double = BODY_TEXT_MINIMUM,
    ) {
        val ratio = contrastRatio(foreground, background)
        println("CONTRAST %-6s %-22s %.2f:1  (min %.1f)".format(theme, label, ratio, minimum))
        assertTrue(
            ratio >= minimum,
            "$theme $label contrast is %.2f:1, below the %.1f:1 minimum".format(ratio, minimum),
        )
    }

    private companion object {
        const val BODY_TEXT_MINIMUM = 4.5
        const val LARGE_TEXT_MINIMUM = 3.0

        fun contrastRatio(a: Color, b: Color): Double {
            val la = relativeLuminance(a)
            val lb = relativeLuminance(b)
            val lighter = maxOf(la, lb)
            val darker = minOf(la, lb)
            return (lighter + 0.05) / (darker + 0.05)
        }

        // WCAG 2.1 relative luminance: linearise each sRGB channel, then weight.
        fun relativeLuminance(color: Color): Double {
            fun channel(value: Float): Double {
                val c = value.toDouble()
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(color.red) +
                0.7152 * channel(color.green) +
                0.0722 * channel(color.blue)
        }
    }
}
