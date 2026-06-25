package com.najdev.snapvault.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Dark palette ─────────────────────────────────────────────────────────────
val SlateDark = Color(0xFF081425)
val SlateDim = Color(0xFF081425)
val SurfaceContainer = Color(0xFF152031)
val SurfaceContainerHigh = Color(0xFF1F2A3C)
val SurfaceContainerHighest = Color(0xFF2A3548)
val SurfaceContainerLow = Color(0xFF111C2D)
val SurfaceContainerLowest = Color(0xFF040E1F)

val PrimaryPurple = Color(0xFFD0BCFF)
val PrimaryContainer = Color(0xFFA078FF)
val SecondaryBlue = Color(0xFFBEC6E0)
val TertiaryCyan = Color(0xFF7BD0FF)
val InfoBlue = Color(0xFF38BDF8)
val ElectricPurple = Color(0xFF8B5CF6)

val OnBackground = Color(0xFFD8E3FB)
val OnSurfaceVariant = Color(0xFFCBC3D7)
val Outline = Color(0xFF958EA0)

val SnapVaultColorScheme: ColorScheme = darkColorScheme(
    primary = PrimaryPurple,
    onPrimary = Color(0xFF3C0091),
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
    primary = Color(0xFF6D3BD7),
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

object SnapVaultColors {
    val electricPurple: Color
        @Composable
        get() = if (LocalThemeIsDark.current) Color(0xFF8B5CF6) else Color(0xFF6D3BD7)

    val success: Color
        @Composable
        get() = if (LocalThemeIsDark.current) Color(0xFF4ADE80) else Color(0xFF15803D)

    val warning: Color
        @Composable
        get() = if (LocalThemeIsDark.current) Color(0xFFFBBF24) else Color(0xFFB45309)

    val info: Color
        @Composable
        get() = if (LocalThemeIsDark.current) Color(0xFF38BDF8) else Color(0xFF0369A1)

    val error: Color
        @Composable
        get() = if (LocalThemeIsDark.current) Color(0xFFF87171) else Color(0xFFB91C1C)
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
