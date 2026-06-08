package com.najdev.snapvault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun DraggableArea(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = modifier, content = content)
}
