package com.najdev.snapvault

import androidx.compose.ui.window.ComposeUIViewController
import com.najdev.snapvault.metadata.IosMediaProcessor
import okio.FileSystem
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    val mediaProcessor = IosMediaProcessor()
    App(
        htmlPicker = { null }, // TODO: Implement UIDocumentPickerViewController
        folderPicker = { null }, // TODO: Implement UIDocumentPickerViewController
        mediaProcessor = mediaProcessor,
        fileSystem = FileSystem.SYSTEM
    )
}
