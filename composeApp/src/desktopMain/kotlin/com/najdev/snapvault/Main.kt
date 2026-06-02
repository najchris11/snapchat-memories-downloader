package com.najdev.snapvault

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.najdev.snapvault.metadata.DesktopMediaProcessor
import okio.FileSystem
import java.awt.FileDialog
import java.awt.Frame

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SnapVault — Snapchat Memories Downloader"
    ) {
        val mediaProcessor = DesktopMediaProcessor()
        val pickers = rememberPlatformPickers()
        
        App(
            pickers = pickers,
            mediaProcessor = mediaProcessor,
            fileSystem = FileSystem.SYSTEM
        )
    }
}
