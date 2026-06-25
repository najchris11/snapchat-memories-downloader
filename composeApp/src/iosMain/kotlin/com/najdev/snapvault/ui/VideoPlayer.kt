package com.najdev.snapvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitViewController
import platform.AVFoundation.AVPlayer
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL

@Composable
actual fun VideoPlayer(videoPath: String, modifier: Modifier) {
    val url = NSURL.fileURLWithPath(videoPath)
    val player = AVPlayer.playerWithURL(url)
    val playerViewController = AVPlayerViewController().apply {
        this.player = player
        player.play()
    }
    UIKitViewController(
        factory = { playerViewController },
        modifier = modifier,
        update = { }
    )
}
