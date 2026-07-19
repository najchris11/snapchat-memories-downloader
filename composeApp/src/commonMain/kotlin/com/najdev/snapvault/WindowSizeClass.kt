package com.najdev.snapvault

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowSize { Compact, Medium, Expanded }
enum class LayoutOverride { Auto, Compact, Expanded }

fun getActiveWindowSize(width: Dp, override: LayoutOverride): WindowSize {
    return when (override) {
        LayoutOverride.Compact -> WindowSize.Compact
        LayoutOverride.Expanded -> WindowSize.Expanded
        LayoutOverride.Auto -> when {
            width < 600.dp -> WindowSize.Compact
            width < 840.dp -> WindowSize.Medium
            else -> WindowSize.Expanded
        }
    }
}
