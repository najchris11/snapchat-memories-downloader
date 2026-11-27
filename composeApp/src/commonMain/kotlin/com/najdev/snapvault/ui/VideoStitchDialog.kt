package com.najdev.snapvault.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.najdev.snapvault.VideoStitchResult
import com.najdev.snapvault.stitchVideos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

@Composable
internal fun VideoStitchDialog(
    videos: List<LibraryItem>,
    outputFolder: String,
    onSuccess: (String) -> Unit,
    onDismiss: () -> Unit,
    stitch: suspend (List<String>, String) -> VideoStitchResult = ::stitchVideos,
) {
    var ordered by remember { mutableStateOf(emptyList<LibraryItem>()) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<VideoStitchResult?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 700.dp).fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.stitch_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.stitch_helper), style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(videos.filter { it.type == "video" }, key = { it.id }) { video ->
                        TextButton(enabled = !busy && ordered.none { it.id == video.id }, onClick = { ordered = ordered + video; failure = null }) {
                            Text(stringResource(Res.string.stitch_add, video.title))
                        }
                    }
                }
                Text(stringResource(Res.string.stitch_order_title), style = MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    itemsIndexed(ordered, key = { _, item -> item.id }) { index, video ->
                        Row {
                            Text(stringResource(Res.string.stitch_order, index + 1, video.title), modifier = Modifier.weight(1f))
                            TextButton(enabled = !busy && index > 0, onClick = {
                                ordered = ordered.toMutableList().apply { add(index - 1, removeAt(index)) }
                            }) { Text(stringResource(Res.string.stitch_up)) }
                            TextButton(enabled = !busy, onClick = { ordered = ordered.filterNot { it.id == video.id }; failure = null }) {
                                Text(stringResource(Res.string.stitch_remove))
                            }
                        }
                    }
                }
                failure?.let { result ->
                    Text(stringResource(when (result) {
                        VideoStitchResult.ToolsMissing -> Res.string.stitch_tools_missing
                        VideoStitchResult.Incompatible -> Res.string.stitch_incompatible
                        VideoStitchResult.InvalidSelection -> Res.string.stitch_invalid
                        else -> Res.string.stitch_failed
                    }), color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = ordered.size >= 2 && !busy, onClick = {
                        busy = true
                        failure = null
                        val paths = ordered.map { it.id }
                        scope.launch {
                            try {
                                when (val result = stitch(paths, outputFolder)) {
                                    is VideoStitchResult.Success -> onSuccess(result.outputPath)
                                    else -> failure = result
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                failure = VideoStitchResult.Failed
                            } finally { busy = false }
                        }
                    }) { Text(stringResource(if (busy) Res.string.stitch_working else Res.string.stitch_run)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(if (busy) Res.string.stitch_cancel else Res.string.stitch_close)) }
                }
            }
        }
    }
}
