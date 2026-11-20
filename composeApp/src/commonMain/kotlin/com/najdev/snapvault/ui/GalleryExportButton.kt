package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.GalleryExportResult
import com.najdev.snapvault.isIosBuild
import com.najdev.snapvault.publishToGallery
import com.najdev.snapvault.supportsGalleryExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

@Composable
internal fun GalleryExportButton(
    path: String,
    supported: Boolean = supportsGalleryExport,
    enabled: Boolean = true,
    applePhotos: Boolean = isIosBuild,
    publish: suspend (String) -> GalleryExportResult = ::publishToGallery,
) {
    if (!supported) return
    val scope = rememberCoroutineScope()
    var busy by remember(path) { mutableStateOf(false) }
    var result by remember(path) { mutableStateOf<GalleryExportResult?>(null) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(enabled = enabled && !busy && result != GalleryExportResult.Saved, onClick = {
            busy = true
            result = null
            scope.launch {
                try { result = publish(path) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { result = GalleryExportResult.Failed }
                finally { busy = false }
            }
        }) { Text(stringResource(if (busy) Res.string.gallery_saving else if (applePhotos) Res.string.photos_save else Res.string.gallery_save)) }
        result?.let { outcome -> Text(stringResource(when (outcome) {
            GalleryExportResult.Saved -> if (applePhotos) Res.string.photos_saved else Res.string.gallery_saved
            GalleryExportResult.Failed -> Res.string.gallery_failed
            GalleryExportResult.Unsupported -> Res.string.gallery_unsupported
        }), style = MaterialTheme.typography.bodySmall) }
    }
}
