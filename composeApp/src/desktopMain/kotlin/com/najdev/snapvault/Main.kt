package com.najdev.snapvault

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.Res
import snapchat_memories_downloader.composeapp.generated.resources.ic_launcher
import snapchat_memories_downloader.composeapp.generated.resources.window_title

fun main() {
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("sun.java2d.dpiaware", "true")

    application {
        val windowState = rememberWindowState(
            placement = WindowPlacement.Floating,
            size = DpSize(1280.dp, 820.dp)
        )

        // The OS close (Alt+F4, the Dock's Quit, a logout) is routed into App rather than
        // straight to exitApplication, so it waits for favorites to save like the title-bar
        // button does. App calls onCloseWindow once that is done.
        var closeRequests by remember { mutableStateOf(0) }

        // macOS keeps its native title bar and traffic lights; a borderless window there reads
        // as a dialog to window managers such as AeroSpace, which float it instead of tiling
        // it (D21). Elsewhere the app draws its own.
        val chrome = windowChromeFor(System.getProperty("os.name").orEmpty())

        Window(
            onCloseRequest = { closeRequests++ },
            state = windowState,
            // The taskbar entry, the alt-tab card and the window manager all read this, and on
            // macOS it is also the title bar.
            title = stringResource(Res.string.window_title),
            undecorated = chrome.undecorated,
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
                showWindowControls = chrome.customWindowControls,
                closeRequests = closeRequests,
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
