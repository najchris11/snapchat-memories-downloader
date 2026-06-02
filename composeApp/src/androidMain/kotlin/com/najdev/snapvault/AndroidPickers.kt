package com.najdev.snapvault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberPlatformPickers(): PlatformPickers {
    // This is a placeholder. In a real app, we might use a CompositionLocal 
    // to provide the pickers from the Activity.
    return remember { 
        object : PlatformPickers {
            override fun pickHtmlFile(onResult: (String?) -> Unit) {
                onResult(null)
            }
            override fun pickFolder(onResult: (String?) -> Unit) {
                onResult(null)
            }
        }
    }
}
