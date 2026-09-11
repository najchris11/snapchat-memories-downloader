package com.najdev.snapvault.ui

import androidx.compose.runtime.Composable
import com.najdev.snapvault.Screen
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.Res
import snapchat_memories_downloader.composeapp.generated.resources.nav_dashboard
import snapchat_memories_downloader.composeapp.generated.resources.nav_library
import snapchat_memories_downloader.composeapp.generated.resources.nav_settings

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
