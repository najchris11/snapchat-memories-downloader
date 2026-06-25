package com.najdev.snapvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.VideoView
import android.net.Uri
import android.widget.MediaController

@Composable
actual fun VideoPlayer(videoPath: String, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(Uri.parse(videoPath))
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                start()
            }
        },
        modifier = modifier,
        update = { view ->
            view.setVideoURI(Uri.parse(videoPath))
            view.start()
        }
    )
}
