package com.najdev.snapvault.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Burns a Snapchat caption/sticker PNG into an MP4 with Media3 Transformer, writing to
 * [output] (a staged path; the caller publishes it). Audio is kept.
 *
 * The overlay is drawn at its pixel size, so it is first scaled to the displayed frame — the
 * encoded size with a 90°/270° rotation swapped — which is what Snapchat's overlay covers.
 *
 * Transformer must be driven from one Looper thread. Cancelling the coroutine posts
 * [Transformer.cancel] there and then waits for it to have run, so the muxer has released
 * [output] before the caller's staging cleanup deletes it (the same drain the iOS exporter does).
 */
@OptIn(UnstableApi::class)
internal suspend fun exportAndroidVideoOverlay(context: Context, main: String, overlay: String, output: String): Boolean {
    val (width, height) = displaySize(main) ?: return false
    val decoded = BitmapFactory.decodeFile(overlay) ?: return false
    val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
    if (scaled !== decoded) decoded.recycle()

    val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(main))))
        .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(scaled))))))
        .build()

    val thread = HandlerThread("snapvault-video-overlay").apply { start() }
    val handler = Handler(thread.looper)
    val stopped = CompletableDeferred<Unit>()
    var transformer: Transformer? = null
    return try {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                handler.post {
                    transformer?.cancel()
                    stopped.complete(Unit)
                }
            }
            handler.post {
                if (!continuation.isActive) {
                    stopped.complete(Unit)
                    return@post
                }
                try {
                    transformer = Transformer.Builder(context)
                        .setLooper(thread.looper)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                stopped.complete(Unit)
                                if (continuation.isActive) continuation.resume(true)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                stopped.complete(Unit)
                                if (continuation.isActive) continuation.resume(false)
                            }
                        })
                        .build()
                        .also { it.start(edited, output) }
                } catch (e: Exception) {
                    Log.w("SnapVault", "Video overlay export could not start", e)
                    stopped.complete(Unit)
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }
    } finally {
        withContext(NonCancellable) { stopped.await() }
        thread.quitSafely()
        scaled.recycle()
    }
}

private fun displaySize(path: String): Pair<Int, Int>? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (width <= 0 || height <= 0) null else if (rotation % 180 != 0) height to width else width to height
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}
