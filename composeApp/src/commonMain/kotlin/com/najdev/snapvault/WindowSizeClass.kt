package com.najdev.snapvault

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowSize { Compact, Medium, Expanded }
enum class LayoutOverride { Auto, Compact, Expanded }

/**
 * The narrowest window that gets the desktop layout. That layout needs a 220dp sidebar, a
 * 280dp inspector and a usable grid between them; 840dp (Material's breakpoint) left an
 * unfolded iPhone Duo with under 400dp of grid. Still below the 1280dp desktop default.
 */
internal val EXPANDED_MIN_WIDTH = 1100.dp

private val COMPACT_MAX_WIDTH = 600.dp

fun getActiveWindowSize(width: Dp, override: LayoutOverride): WindowSize {
    return when (override) {
        LayoutOverride.Compact -> WindowSize.Compact
        LayoutOverride.Expanded -> WindowSize.Expanded
        LayoutOverride.Auto -> when {
            width < COMPACT_MAX_WIDTH -> WindowSize.Compact
            width < EXPANDED_MIN_WIDTH -> WindowSize.Medium
            else -> WindowSize.Expanded
        }
    }
}
