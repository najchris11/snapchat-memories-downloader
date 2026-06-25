package com.najdev.snapvault

import androidx.compose.runtime.Composable

interface PlatformPickers {
    fun pickHtmlFile(onResult: (String?) -> Unit)
    fun pickFolder(onResult: (String?) -> Unit)
    fun pickMultipleZips(onResult: (List<String>) -> Unit)
}

@Composable
expect fun rememberPlatformPickers(): PlatformPickers
