package com.najdev.snapvault

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowSize { Compact, Medium, Expanded }
enum class LayoutOverride { Auto, Compact, Expanded }

// Compact is the single-column layout with a bottom nav. It runs up to 1000dp, well past phone
// widths, because half of a 14" laptop screen is roughly 760–900dp and the sidebar layout looks
// cramped there. Compact is also used for Android tablets in portrait.
internal val COMPACT_MAX_WIDTH = 1000.dp
internal val MEDIUM_MAX_WIDTH = 1200.dp

fun getActiveWindowSize(width: Dp, override: LayoutOverride): WindowSize {
    return when (override) {
        LayoutOverride.Compact -> WindowSize.Compact
        LayoutOverride.Expanded -> WindowSize.Expanded
        LayoutOverride.Auto -> when {
            width < COMPACT_MAX_WIDTH -> WindowSize.Compact
            width < MEDIUM_MAX_WIDTH -> WindowSize.Medium
            else -> WindowSize.Expanded
        }
    }
}
