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

    override fun pickMultipleZips(onResult: (List<String>) -> Unit) {
        // TODO: Implement UIDocumentPickerViewController with multi-select for zip files
        onResult(emptyList())
    }
}

@Composable
actual fun rememberPlatformPickers(): PlatformPickers = remember { IosPickers() }
