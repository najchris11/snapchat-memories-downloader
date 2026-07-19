package com.najdev.snapvault

import androidx.compose.ui.window.ComposeUIViewController
import com.najdev.snapvault.downloader.NoOpZipPipelineRunner
import com.najdev.snapvault.metadata.IosMediaProcessor
import okio.FileSystem
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    val mediaProcessor = IosMediaProcessor()
    val pickers = rememberPlatformPickers()
    App(
        pickers = pickers,
        mediaProcessor = mediaProcessor,
        zipPipelineRunner = NoOpZipPipelineRunner,
        fileSystem = FileSystem.SYSTEM
    )
}
