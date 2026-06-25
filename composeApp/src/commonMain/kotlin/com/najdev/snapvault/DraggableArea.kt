package com.najdev.snapvault

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun DraggableArea(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit)
