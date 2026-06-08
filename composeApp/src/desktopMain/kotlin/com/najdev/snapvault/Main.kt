package com.najdev.snapvault

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.najdev.snapvault.downloader.DesktopZipPipelineRunner
import com.najdev.snapvault.metadata.DesktopMediaProcessor
import okio.FileSystem
import org.jetbrains.compose.resources.painterResource
import snapchat_memories_downloader.composeapp.generated.resources.Res
import snapchat_memories_downloader.composeapp.generated.resources.ic_launcher

fun main() {
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("sun.java2d.dpiaware", "true")

    application {
        val windowState = rememberWindowState(
            placement = WindowPlacement.Floating,
            size = DpSize(1280.dp, 820.dp)
        )

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            undecorated = true,
            transparent = false,
            icon = painterResource(Res.drawable.ic_launcher)
        ) {
            val mediaProcessor = DesktopMediaProcessor()
            val zipPipelineRunner = DesktopZipPipelineRunner(mediaProcessor)
            val pickers = rememberPlatformPickers()

            CompositionLocalProvider(LocalAwtWindow provides window) {
            App(
                pickers = pickers,
                mediaProcessor = mediaProcessor,
                zipPipelineRunner = zipPipelineRunner,
                fileSystem = FileSystem.SYSTEM,
                showWindowControls = true,
                onCloseWindow = ::exitApplication,
                onMinimizeWindow = { windowState.isMinimized = true },
                onMaximizeWindow = {
                    windowState.placement = if (windowState.placement == WindowPlacement.Maximized)
                        WindowPlacement.Floating else WindowPlacement.Maximized
                }
            )
            } // end CompositionLocalProvider
        }
    }
}
