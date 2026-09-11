package com.najdev.snapvault.ui

import androidx.compose.runtime.Composable
import com.najdev.snapvault.LayoutOverride
import com.najdev.snapvault.Screen
import com.najdev.snapvault.ThemeMode
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.Res
import snapchat_memories_downloader.composeapp.generated.resources.nav_dashboard
import snapchat_memories_downloader.composeapp.generated.resources.nav_library
import snapchat_memories_downloader.composeapp.generated.resources.nav_settings
import snapchat_memories_downloader.composeapp.generated.resources.set_layout_auto
import snapchat_memories_downloader.composeapp.generated.resources.set_layout_compact
import snapchat_memories_downloader.composeapp.generated.resources.set_layout_expanded
import snapchat_memories_downloader.composeapp.generated.resources.set_theme_dark
import snapchat_memories_downloader.composeapp.generated.resources.set_theme_light
import snapchat_memories_downloader.composeapp.generated.resources.set_theme_system

/**
 * The label for a destination, wherever it is drawn.
 *
 * `AppSidebar` read these from resources while `PhoneRoot` passed the same three words as
 * literals: one navigation, two sources of truth, and only one of them translatable. Hanging
 * the label off the destination itself leaves a single place to change, rather than two that
 * a test can only watch for drift.
 */
@Composable
fun Screen.navLabel(): String = when (this) {
    Screen.Dashboard -> stringResource(Res.string.nav_dashboard)
    Screen.Library -> stringResource(Res.string.nav_library)
    Screen.Settings -> stringResource(Res.string.nav_settings)
}

/**
 * Theme and layout options are rendered to the user, so they need labels rather than enum
 * constant names. `LayoutOverride` was drawn as `option.name` directly — which reads as
 * English only by accident of how the constants are spelled, and cannot be translated at all.
 */
@Composable
fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> stringResource(Res.string.set_theme_system)
    ThemeMode.LIGHT -> stringResource(Res.string.set_theme_light)
    ThemeMode.DARK -> stringResource(Res.string.set_theme_dark)
}

@Composable
fun LayoutOverride.label(): String = when (this) {
    LayoutOverride.Auto -> stringResource(Res.string.set_layout_auto)
    LayoutOverride.Compact -> stringResource(Res.string.set_layout_compact)
    LayoutOverride.Expanded -> stringResource(Res.string.set_layout_expanded)
}
