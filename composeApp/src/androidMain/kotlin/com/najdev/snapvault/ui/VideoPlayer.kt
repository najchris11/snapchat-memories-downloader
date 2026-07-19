package com.najdev.snapvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.VideoView
import android.net.Uri
import android.widget.MediaController

@Composable
actual fun VideoPlayer(videoPath: String, modifier: Modifier) {
    val lastPath = remember { mutableStateOf("") }
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setVideoURI(Uri.parse(videoPath))
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                start()
                lastPath.value = videoPath
            }
        },
        modifier = modifier,
        update = { view ->
            if (videoPath != lastPath.value) {
                view.setVideoURI(Uri.parse(videoPath))
                view.start()
                lastPath.value = videoPath
            }
        }
    )
}
