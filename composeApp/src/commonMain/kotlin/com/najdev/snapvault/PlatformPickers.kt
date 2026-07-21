package com.najdev.snapvault

import androidx.compose.runtime.Composable

interface PlatformPickers {
    fun pickHtmlFile(onResult: (String?) -> Unit)
    fun pickFolder(onResult: (String?) -> Unit) {
        pickOutputFolder(onResult)
    }
    fun pickOutputFolder(onResult: (String?) -> Unit)
    fun pickZipFolder(onResult: (String?) -> Unit)
    fun pickMultipleZips(onResult: (List<String>) -> Unit)
    fun releaseAllSecurityAccess() {}
}

@Composable
expect fun rememberPlatformPickers(): PlatformPickers
