package com.najdev.snapvault.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SlateDark = Color(0xFF081425)
val SlateBright = Color(0xFF2F3A4C)
val SurfaceContainer = Color(0xFF152031)
val SurfaceContainerHigh = Color(0xFF1F2A3C)
val SurfaceContainerLowest = Color(0xFF040E1F)
val ElectricPurple = Color(0xFF8B5CF6)
val InfoBlue = Color(0xFF38BDF8)
val OnBackground = Color(0xFFD8E3FB)
val OnSurfaceVariant = Color(0xFFCBC3D7)

val SnapVaultColorScheme: ColorScheme = darkColorScheme(
    primary = ElectricPurple,
    onPrimary = Color.White,
    secondary = InfoBlue,
    background = SlateDark,
    onBackground = OnBackground,
    surface = SurfaceContainer,
    onSurface = OnBackground,
    surfaceVariant = SurfaceContainerHigh,
    onSurfaceVariant = OnSurfaceVariant,
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
