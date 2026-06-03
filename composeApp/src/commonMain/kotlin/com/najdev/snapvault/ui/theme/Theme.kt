package com.najdev.snapvault.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Pro Palette from Stitch Design
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
    outline = Outline,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun SnapVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SnapVaultColorScheme,
        content = content
    )
}
