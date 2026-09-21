package com.najdev.snapvault.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.VideoView
import android.net.Uri
import android.widget.MediaController

@Composable
actual fun VideoPlayer(videoPath: String, modifier: Modifier) {
    // AndroidView's `update` runs on every recomposition, so setting the URI and calling
    // start() there restarted playback whenever any parent recomposed — which, now that the
    // Library dialog actually reaches this, happens on every thumbnail load and selection
    // change. Loading happens once in `factory` instead, and `key` recreates the view only
    // when the path genuinely changes.
    key(videoPath) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    val controller = MediaController(context)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setVideoURI(Uri.parse(videoPath))
                    start()
                }
            },
            modifier = modifier,
            onRelease = { view -> view.stopPlayback() },
        )
    }
}
