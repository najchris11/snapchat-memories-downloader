package com.najdev.snapvault.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.najdev.snapvault.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

/** Offline land outlines and known coordinates only. No tile service or network client. */
@Composable
internal fun GpsMapDialog(items: List<LibraryItem>, onOpenItem: (LibraryItem) -> Unit, onDismiss: () -> Unit) {
    val points = remember(items) { libraryMapPoints(items) }
    val land by produceState<List<List<Offset>>?>(null) {
        value = withContext(ioDispatcher) {
            Json.parseToJsonElement(Res.readBytes("files/world-land.json").decodeToString()).jsonArray.map { ring ->
                ring.jsonArray.map { point ->
                    val pair = point.jsonArray
                    Offset(((pair[0].jsonPrimitive.double + 180) / 360).toFloat(), ((90 - pair[1].jsonPrimitive.double) / 180).toFloat())
                }
            }
        }
    }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val water = MaterialTheme.colorScheme.surfaceContainerLowest
    val continent = MaterialTheme.colorScheme.surfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val marker = MaterialTheme.colorScheme.primary
    val markerOutline = MaterialTheme.colorScheme.onPrimary
    val description = stringResource(Res.string.map_canvas)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.map_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.map_privacy), style = MaterialTheme.typography.bodySmall)
                if (points.isEmpty()) {
                    Text(stringResource(Res.string.map_empty))
                } else {
                    Text(pluralStringResource(Res.plurals.map_count, points.size, points.size))
                    Canvas(Modifier.fillMaxWidth().aspectRatio(2f).clipToBounds().background(water)
                        .semantics { contentDescription = description }
                        .pointerInput(points, zoom, pan) {
                            detectTapGestures { tap ->
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val hit = points.minByOrNull { point ->
                                    val position = center + (Offset(point.x * size.width, point.y * size.height) - center) * zoom + pan
                                    (position - tap).getDistanceSquared()
                                }
                                hit?.let { point ->
                                    val position = center + (Offset(point.x * size.width, point.y * size.height) - center) * zoom + pan
                                    if ((position - tap).getDistance() <= 20.dp.toPx()) onOpenItem(point.item)
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, movement, scale, _ ->
                                zoom = (zoom * scale).coerceIn(1f, 8f)
                                val limitX = size.width * (zoom - 1) / 2
                                val limitY = size.height * (zoom - 1) / 2
                                pan = Offset((pan.x + movement.x).coerceIn(-limitX, limitX), (pan.y + movement.y).coerceIn(-limitY, limitY))
                            }
                        }) {
                        fun position(x: Float, y: Float) = center + (Offset(x * size.width, y * size.height) - center) * zoom + pan
                        val path = Path().apply { fillType = PathFillType.EvenOdd }
                        land.orEmpty().forEach { ring ->
                            ring.forEachIndexed { index, coordinate ->
                                val p = position(coordinate.x, coordinate.y)
                                if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                            }
                            path.close()
                        }
                        drawPath(path, continent)
                        for (step in 1..5) drawLine(grid, position(step / 6f, 0f), position(step / 6f, 1f))
                        for (step in 1..2) drawLine(grid, position(0f, step / 3f), position(1f, step / 3f))
                        points.forEach { point ->
                            val p = position(point.x, point.y)
                            drawCircle(markerOutline, 6.dp.toPx(), p)
                            drawCircle(marker, 4.dp.toPx(), p)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(8f) }) { Text(stringResource(Res.string.map_zoom_in)) }
                        TextButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f); pan = Offset.Zero }) { Text(stringResource(Res.string.map_zoom_out)) }
                        TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text(stringResource(Res.string.map_reset)) }
                    }
                    LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(points, key = { it.item.id }) { point ->
                            TextButton(onClick = { onOpenItem(point.item) }) {
                                Text(stringResource(Res.string.map_memory, point.item.title, point.item.date))
                            }
                        }
                    }
                }
                Text(stringResource(Res.string.map_attribution), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.map_close)) }
            }
        }
    }
}
