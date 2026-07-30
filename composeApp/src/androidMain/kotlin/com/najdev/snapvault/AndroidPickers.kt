package com.najdev.snapvault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberPlatformPickers(): PlatformPickers {
    return remember { 
        object : PlatformPickers {
            override fun pickHtmlFile(onResult: (String?) -> Unit) {
                onResult(null)
            }
            override fun pickFolder(onResult: (String?) -> Unit) {
                onResult(null)
            }
            override fun pickOutputFolder(onResult: (String?) -> Unit) {
                onResult(null)
            }
            override fun pickZipFolder(onResult: (String?) -> Unit) {
                onResult(null)
            }
            override fun pickMultipleZips(onResult: (List<String>) -> Unit) {
                onResult(emptyList())
            }
        }
    }
}
