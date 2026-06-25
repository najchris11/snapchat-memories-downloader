package com.najdev.snapvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun VideoPlayer(videoPath: String, modifier: Modifier)
