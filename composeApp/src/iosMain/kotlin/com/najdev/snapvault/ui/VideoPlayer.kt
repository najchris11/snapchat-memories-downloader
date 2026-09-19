package com.najdev.snapvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun VideoPlayer(videoPath: String, modifier: Modifier) {
    // The player and its controller used to be constructed in the composable body, so every
    // recomposition built a new AVPlayer and restarted from zero. They are remembered
    // against the path instead, and playback is started as an effect rather than as a side
    // effect of composition.
    val playerViewController = remember(videoPath) {
        AVPlayerViewController().apply {
            player = AVPlayer.playerWithURL(NSURL.fileURLWithPath(videoPath))
        }
    }

    LaunchedEffect(playerViewController) {
        playerViewController.player?.play()
    }

    DisposableEffect(playerViewController) {
        onDispose { playerViewController.player?.pause() }
    }

    UIKitViewController(
        factory = { playerViewController },
        modifier = modifier,
        update = { },
    )
}
