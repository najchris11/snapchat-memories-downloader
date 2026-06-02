package com.najdev.snapvault

import androidx.compose.runtime.Composable

interface PlatformPickers {
    fun pickHtmlFile(onResult: (String?) -> Unit)
    fun pickFolder(onResult: (String?) -> Unit)
}

@Composable
expect fun rememberPlatformPickers(): PlatformPickers
