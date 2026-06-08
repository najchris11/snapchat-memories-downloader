package com.najdev.snapvault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

class IosPickers : PlatformPickers {
    override fun pickHtmlFile(onResult: (String?) -> Unit) {
        // TODO: Implement UIDocumentPickerViewController
        onResult(null)
    }

    override fun pickFolder(onResult: (String?) -> Unit) {
        // TODO: Implement UIDocumentPickerViewController
        onResult(null)
    }

    override fun pickZipFile(onResult: (String?) -> Unit) {
        // TODO: Implement UIDocumentPickerViewController for zip files
        onResult(null)
    }
}

@Composable
actual fun rememberPlatformPickers(): PlatformPickers = remember { IosPickers() }
