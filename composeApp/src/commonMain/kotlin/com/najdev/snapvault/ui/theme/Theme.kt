package com.najdev.snapvault.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Palette constants are private on purpose: they are single-theme values, and reaching for
// one directly from a screen produces a colour that does not adapt when the theme changes.
// Screens go through MaterialTheme.colorScheme, SnapVaultColors, or LogColors instead.

// ── Dark palette ─────────────────────────────────────────────────────────────
private val SlateDark = Color(0xFF081425)
private val SurfaceContainer = Color(0xFF152031)
private val SurfaceContainerHigh = Color(0xFF1F2A3C)
private val SurfaceContainerHighest = Color(0xFF2A3548)
private val SurfaceContainerLow = Color(0xFF111C2D)
private val SurfaceContainerLowest = Color(0xFF040E1F)

private val PrimaryContainer = Color(0xFFA078FF)
private val SecondaryBlue = Color(0xFFBEC6E0)
private val TertiaryCyan = Color(0xFF7BD0FF)

private val OnBackground = Color(0xFFD8E3FB)
private val OnSurfaceVariant = Color(0xFFCBC3D7)
private val Outline = Color(0xFF958EA0)

// Accents that exist in both themes. Declared once and referenced by both the colour
// schemes and SnapVaultColors, rather than the hex being retyped in each place.
private val ElectricPurpleDark = Color(0xFF8B5CF6)
private val OnElectricPurple = Color(0xFF150030)
private val ElectricPurpleLight = Color(0xFF6D3BD7)
private val SuccessDark = Color(0xFF4ADE80)
private val SuccessLight = Color(0xFF15803D)
private val WarningDark = Color(0xFFFBBF24)
private val WarningLight = Color(0xFFB45309)
// The amber warning is bright in dark mode and brown in light, so what reads on it flips.
private val OnWarningDark = Color(0xFF2B1A00)
private val OnWarningLight = Color(0xFFFFFFFF)
private val InfoDark = Color(0xFF38BDF8)
private val InfoLight = Color(0xFF0369A1)

val SnapVaultColorScheme: ColorScheme = darkColorScheme(
    primary = ElectricPurpleDark,
    // Dark, not white. #8B5CF6 sits at a tone where white text reaches only 4.23:1 —
    // below AA — so the readable pairing on a filled violet surface is a near-black.
    // ThemeContrastTest pins this.
    onPrimary = OnElectricPurple,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = Color(0xFF340080),
    secondary = SecondaryBlue,
    onSecondary = Color(0xFF283044),
    tertiary = TertiaryCyan,
    onTertiary = Color(0xFF00354A),
    background = SlateDark,
    onBackground = OnBackground,
    surface = SurfaceContainer,
    onSurface = OnBackground,
    surfaceVariant = SurfaceContainerHigh,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    outline = Outline,
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

// ── Light palette ─────────────────────────────────────────────────────────────
val SnapVaultLightColorScheme: ColorScheme = lightColorScheme(
    primary = ElectricPurpleLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE0FF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF5B5D71),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF006687),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF0D1525),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D1525),
    surfaceVariant = Color(0xFFE8ECF4),
    onSurfaceVariant = Color(0xFF454558),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F7FC),
    surfaceContainer = Color(0xFFEFF1F8),
    surfaceContainerHigh = Color(0xFFE8ECF4),
    surfaceContainerHighest = Color(0xFFE0E5F0),
    outline = Color(0xFF767688),
    outlineVariant = Color(0xFFC8C6D7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

val LocalThemeIsDark = staticCompositionLocalOf { true }

/**
 * Semantic roles Material 3 does not provide. Each resolves against the active theme.
 *
 * There is deliberately no `error` here — [MaterialTheme.colorScheme.error] is the one error
 * colour, and having a second produced two different reds for the same meaning. There is no
 * `electricPurple` either: the brand violet *is* [MaterialTheme.colorScheme.primary], so
 * every Material default resolves to it instead of needing an override per call site.
 */
object SnapVaultColors {
    val success: Color
        @Composable
        get() = if (LocalThemeIsDark.current) SuccessDark else SuccessLight

    val warning: Color
        @Composable
        get() = if (LocalThemeIsDark.current) WarningDark else WarningLight

    /** Foreground for anything drawn *on* [warning] — a filled chip or step circle. */
    val onWarning: Color
        @Composable
        get() = if (LocalThemeIsDark.current) OnWarningDark else OnWarningLight

    val info: Color
        @Composable
        get() = if (LocalThemeIsDark.current) InfoDark else InfoLight
}

/**
 * The pipeline log renders as a terminal in both themes — a dark ground with a light
 * foreground — so these are fixed rather than theme-derived.
 *
 * This is why they cannot come from [SnapVaultColors]: the panel used to be a translucent
 * `Color.Black` while its text used `onSurface`, which in the light theme is near-black, so
 * the log was dark text on a dark grey panel. Making the ground reliably dark means every
 * colour drawn on it has to be a dark-ground colour too, in both themes — a light-theme
 * `success` green (#15803D) on this surface would be just as unreadable as the text was.
 */
object LogColors {
    val surface = Color(0xFF0A1220)
    val onSurface = Color(0xFFDCE3F2)
    val prompt = ElectricPurpleDark
    val success = SuccessDark
    val warning = WarningDark
    val info = InfoDark
    val error = Color(0xFFF87171)
    val muted = Color(0xFF9AA8C4)
}

/**
 * Colours for things drawn *on top of media* — letterboxing, hover washes, badge backings,
 * the preview dimmer, and the text and icons sitting on them.
 *
 * Fixed in both themes on purpose, and named on purpose. These sit over a photograph, not
 * over a theme surface, so following the theme would be wrong. Until now they were written
 * as bare `Color.Black` / `Color.White`, which is indistinguishable from the two light-theme
 * bugs round 1 fixed — so nobody reading or grepping the file could tell a deliberate
 * media colour from a forgotten one. Naming the role is the whole point: a bare
 * `Color.Black` in a UI file is now a defect by definition.
 *
 * Contrast is not asserted here the way [SnapVaultColors] pairs are. What sits behind a
 * scrim is a user's photo, so the effective ratio is unknowable — the scrim levels are
 * chosen to be heavy enough that [onMedia] stays legible over a bright image.
 */
object MediaColors {
    /** Opaque ground behind letterboxed photos and video. */
    val letterbox = Color(0xFF000000)

    /** Light wash over a thumbnail, carrying a hover affordance. */
    val scrimHover = Color(0x33000000)

    /** The same wash while actively hovered. */
    val scrimHoverStrong = Color(0x66000000)

    /** Backing for a badge or gradient that has to stay readable over arbitrary media. */
    val scrimBadge = Color(0x8C000000)

    /** Full-screen dimmer behind the preview dialog. */
    val scrimDialog = Color(0xE0000000)

    /** Text and icons drawn on media or on any scrim above. */
    val onMedia = Color(0xFFFFFFFF)

    /** De-emphasised foreground on media — placeholder glyphs. */
    val onMediaMuted = Color(0x40FFFFFF)
}

@Composable
fun SnapVaultTheme(darkMode: Boolean = true, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalThemeIsDark provides darkMode) {
        MaterialTheme(
            colorScheme = if (darkMode) SnapVaultColorScheme else SnapVaultLightColorScheme,
            content = content
        )
    }
}
