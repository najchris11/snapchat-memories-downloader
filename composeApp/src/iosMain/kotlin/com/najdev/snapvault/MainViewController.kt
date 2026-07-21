package com.najdev.snapvault

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.remember
import com.najdev.snapvault.downloader.IosZipPipelineRunner
import com.najdev.snapvault.metadata.IosMediaProcessor
import okio.FileSystem
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController {
    val mediaProcessor = remember { IosMediaProcessor() }
    val zipPipelineRunner = remember { IosZipPipelineRunner(mediaProcessor) }
    val pickers = rememberPlatformPickers()
    App(
        pickers = pickers,
        mediaProcessor = mediaProcessor,
        zipPipelineRunner = zipPipelineRunner,
        fileSystem = FileSystem.SYSTEM
    )
}
