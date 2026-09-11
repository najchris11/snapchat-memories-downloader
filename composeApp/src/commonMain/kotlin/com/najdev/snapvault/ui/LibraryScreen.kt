package com.najdev.snapvault.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.getCachedThumbnail
import com.najdev.snapvault.ioDispatcher
import com.najdev.snapvault.scanMediaFiles
import com.najdev.snapvault.ui.theme.MediaColors
import com.najdev.snapvault.ui.theme.SnapVaultColors
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

data class LibraryItem(
    val id: String,
    val date: String,
    val title: String,
    val type: String,
    val duration: String?,
    val hasGps: Boolean,
    val hasOverlay: Boolean,
    val favorited: Boolean = false,
    val fileSizeBytes: Long = 0L
)

@Composable
fun LibraryScreen(
    downloadFolder: String?,
    onOpenFolder: () -> Unit,
    windowSize: WindowSize = WindowSize.Expanded,
) {
    // The inspector is a hard 280dp sibling column. Alongside a 160dp-minimum adaptive grid
    // and 24dp padding it left roughly 72dp for the grid on a 400dp window — less than half
    // of one cell — so it is Expanded-only. Everything it shows for a selected item is also
    // in MediaPreviewDialog, which a tap already opens.
    val showInspector = windowSize == WindowSize.Expanded
    val compact = windowSize == WindowSize.Compact

    var refreshKey by remember { mutableStateOf(0) }
    // Off the UI thread: scanning stats every file in the folder, which visibly hitches
    // composition for large libraries.
    val items by produceState(emptyList<LibraryItem>(), downloadFolder, refreshKey) {
        value = if (downloadFolder != null) {
            withContext(ioDispatcher) { scanMediaFiles(downloadFolder) }
        } else emptyList()
    }

    var selectedFilter by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }
    var selectedItem by remember { mutableStateOf<LibraryItem?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    val filteredItems = remember(items, selectedFilter, searchQuery) {
        items
            .filter { item ->
                when (selectedFilter) {
                    "Photos" -> item.type == "photo"
                    "Videos" -> item.type == "video"
                    else -> true
                }
            }
            .filter { item ->
                searchQuery.isBlank() || item.title.contains(searchQuery, ignoreCase = true)
            }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // ── Main content ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(if (compact) 16.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Scrolls rather than overflowing: four chips need more width than a phone
                // has, and the row sits next to the refresh button.
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatChip(
                        icon = Icons.Outlined.PhotoLibrary,
                        label = "${items.size} Memories",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    StatChip(
                        icon = Icons.Outlined.Image,
                        label = "${items.count { it.type == "photo" }} Photos",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    StatChip(
                        icon = Icons.Outlined.Videocam,
                        label = "${items.count { it.type == "video" }} Videos",
                        tint = SnapVaultColors.info
                    )
                    StatChip(
                        icon = Icons.Outlined.GpsFixed,
                        label = "${items.count { it.hasGps }} with GPS",
                        tint = SnapVaultColors.success
                    )
                    // Total size otherwise only appears in the inspector, so without this it
                    // would simply vanish on narrower windows.
                    if (!showInspector && items.isNotEmpty()) {
                        StatChip(
                            icon = Icons.Outlined.Storage,
                            label = formatBytes(items.sumOf { it.fileSizeBytes }),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (downloadFolder != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { refreshKey++ }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Refresh library",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Filter tabs plus search. Side by side these need about 384dp — roughly 184dp
            // of tabs and a fixed 200dp field — which is more than Compact (~328dp) or the
            // narrow end of Medium (~332dp, after the 220dp sidebar) can give, so the field
            // clipped off-screen. Below Expanded they stack, and the field is flexible
            // rather than fixed so it cannot overflow at any width.
            if (showInspector) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LibraryFilterTabs(
                        selected = selectedFilter,
                        onSelect = { selectedFilter = it },
                    )
                    LibrarySearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        modifier = Modifier.width(200.dp),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LibraryFilterTabs(
                        selected = selectedFilter,
                        onSelect = { selectedFilter = it },
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                    LibrarySearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Grid or empty state
            if (filteredItems.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.PhotoLibrary,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            stringResource(Res.string.lib_empty_state),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.widthIn(max = 280.dp)
                        )
                        if (downloadFolder == null) {
                            TextButton(onClick = onOpenFolder) {
                                Text(
                                    "Select Download Folder",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (compact) 130.dp else 160.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredItems) { item ->
                        MediaCard(
                            item = item,
                            selected = item == selectedItem,
                            onClick = {
                                selectedItem = item
                                showPreview = true
                            }
                        )
                    }
                }
            }
        }

        if (showPreview && selectedItem != null) {
            MediaPreviewDialog(
                item = selectedItem!!,
                onDismiss = { showPreview = false }
            )
        }

        // ── Inspector panel (Expanded only) ──────────────────────────────────
        if (showInspector) {
            Surface(
                modifier = Modifier.width(280.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(0.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                AnimatedContent(
                    targetState = selectedItem,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "inspector"
                ) { selected ->
                    if (selected != null) {
                        InspectorItemDetail(
                            item = selected,
                            onPreview = { showPreview = true },
                            onClearSelection = { selectedItem = null }
                        )
                    } else {
                        InspectorGlobalStats(items = items)
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorItemDetail(
    item: LibraryItem,
    onPreview: () -> Unit,
    onClearSelection: () -> Unit
) {
    val isVideo = item.type == "video"
    val thumbnail by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(ioDispatcher) { getCachedThumbnail(item.id) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
    ) {
        // Thumbnail / preview area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                // Was enabled = !isVideo, because the preview dialog could not show a video.
                // It can now, so videos open from here too.
                .clickable { onPreview() },
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail!!,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MediaColors.scrimHover),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isVideo) Icons.Outlined.PlayCircle else Icons.Outlined.ZoomIn,
                        contentDescription = stringResource(
                            if (isVideo) Res.string.lib_play_video else Res.string.lib_open_preview
                        ),
                        tint = MediaColors.onMedia,
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = if (isVideo) Icons.Outlined.PlayCircle else Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                    modifier = Modifier.size(48.dp)
                )
            }

            // Close / deselect
            IconButton(
                onClick = onClearSelection,
                modifier = Modifier.align(Alignment.TopEnd).size(36.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    "Clear selection",
                    tint = MediaColors.onMedia,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Type badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(100))
                    .background(if (isVideo) SnapVaultColors.info.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    item.type.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isVideo) SnapVaultColors.info else MaterialTheme.colorScheme.primary
                )
            }
        }

        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // File name + date
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    item.date,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // Metadata rows
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InspectorDetailRow(
                    label = "SIZE",
                    value = if (item.fileSizeBytes > 0) formatBytes(item.fileSizeBytes) else "—",
                    valueColor = MaterialTheme.colorScheme.primary
                )
                InspectorDetailRow(
                    label = "GPS",
                    value = if (item.hasGps) "Tagged" else "No data",
                    valueColor = if (item.hasGps) SnapVaultColors.success else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                InspectorDetailRow(
                    label = "OVERLAY",
                    value = if (item.hasOverlay) "Combined" else "None",
                    valueColor = if (item.hasOverlay) SnapVaultColors.info else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                item.duration?.let {
                    InspectorDetailRow(label = "DURATION", value = it, valueColor = MaterialTheme.colorScheme.onSurface)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Surface(
                onClick = onPreview,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (isVideo) Icons.Outlined.PlayCircle else Icons.Outlined.ZoomIn,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        stringResource(
                            if (isVideo) Res.string.lib_play_video else Res.string.lib_open_preview
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorDetailRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@Composable
private fun InspectorGlobalStats(items: List<LibraryItem>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(
                stringResource(Res.string.lib_inspector_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        val totalBytes = items.sumOf { it.fileSizeBytes }
        val gpsCount = items.count { it.hasGps }
        val overlayCount = items.count { it.hasOverlay }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(Icons.Outlined.Storage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(11.dp))
                Text(stringResource(Res.string.lib_storage_label), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (items.isEmpty()) "—" else "${items.size} file${if (items.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    if (items.isEmpty()) "—" else formatBytes(totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (items.isNotEmpty()) {
                val photoCount = items.count { it.type == "photo" }
                val videoCount = items.count { it.type == "video" }
                Text(
                    "$photoCount photo${if (photoCount == 1) "" else "s"} · $videoCount video${if (videoCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(Icons.Outlined.Tag, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(11.dp))
                Text(stringResource(Res.string.lib_metadata_label), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
            }
            MetadataRow(
                icon = Icons.Outlined.GpsFixed,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(Res.string.lib_gps_verified),
                subtitle = if (items.isEmpty()) "—" else "$gpsCount item${if (gpsCount == 1) "" else "s"} tagged"
            )
            MetadataRow(
                icon = Icons.Outlined.Layers,
                iconTint = SnapVaultColors.info,
                title = stringResource(Res.string.lib_overlay_detected),
                subtitle = if (items.isEmpty()) "—" else "$overlayCount asset${if (overlayCount == 1) "" else "s"} combined"
            )
        }

    }
}

@Composable
fun StatChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(13.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
fun MetadataRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(iconTint.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(15.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}


@Composable
fun MediaPreviewDialog(item: LibraryItem, onDismiss: () -> Unit) {
    val isVideo = item.type == "video"
    val thumbnail by produceState<ImageBitmap?>(null, item.id) {
        // Videos are rendered by VideoPlayer, which loads its own (cached) frame.
        value = if (isVideo) null else withContext(ioDispatcher) { getCachedThumbnail(item.id) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(MediaColors.scrimDialog).clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 860.dp)
                    .heightIn(max = 680.dp)
                    .clickable { }, // absorb clicks so the scrim handler doesn't fire
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column {
                    // Image / video area
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MediaColors.letterbox),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            // VideoPlayer draws its own cached frame and play affordance, and
                            // hands the file to the system player on click. It has been
                            // implemented on every platform since the Library was written and
                            // was never called — this spot used to read "Video preview not
                            // available" instead.
                            isVideo -> VideoPlayer(
                                videoPath = item.id,
                                modifier = Modifier.fillMaxSize(),
                            )
                            thumbnail != null -> Image(
                                bitmap = thumbnail!!,
                                contentDescription = item.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                            else -> Icon(
                                Icons.Outlined.Image,
                                contentDescription = null,
                                tint = MediaColors.onMediaMuted,
                                modifier = Modifier.size(72.dp)
                            )
                        }

                        // Close button
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(100))
                                    .background(MediaColors.scrimBadge),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Close, "Close", tint = MediaColors.onMedia, modifier = Modifier.size(14.dp))
                            }
                        }
                    }

                    // Info bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.date, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (item.fileSizeBytes > 0) {
                                Text(formatBytes(item.fileSizeBytes), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            if (item.hasGps) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.GpsFixed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(11.dp))
                                    Text("GPS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (item.hasOverlay) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(SnapVaultColors.info.copy(alpha = 0.1f))
                                        .padding(horizontal = 7.dp, vertical = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Layers, null, tint = SnapVaultColors.info, modifier = Modifier.size(11.dp))
                                    Text("OVERLAY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SnapVaultColors.info)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MediaCard(item: LibraryItem, selected: Boolean = false, onClick: () -> Unit = {}) {
    val isVideo = item.type == "video"
    val thumbnail by produceState<ImageBitmap?>(null, item.id) {
        value = withContext(ioDispatcher) { getCachedThumbnail(item.id) }
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column {
            // Thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, MediaColors.scrimBadge)
                            )
                        )
                )

                if (thumbnail == null) {
                    Icon(
                        imageVector = if (isVideo) Icons.Outlined.PlayCircle else Icons.Outlined.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                        modifier = Modifier.size(44.dp)
                    )
                }

                // Type badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(100))
                        .background(if (isVideo) SnapVaultColors.info.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        .border(
                            1.dp,
                            if (isVideo) SnapVaultColors.info.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            RoundedCornerShape(100)
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.type.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isVideo) SnapVaultColors.info else MaterialTheme.colorScheme.primary
                    )
                }

                // Duration badge
                if (item.duration != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MediaColors.scrimBadge)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            item.duration,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MediaColors.onMedia
                        )
                    }
                }

                // Favorite badge
                if (item.favorited) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(7.dp)
                    ) {
                        Icon(
                            Icons.Default.Favorite,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Info row
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.hasGps) Icon(Icons.Outlined.GpsFixed, "GPS", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(11.dp))
                        if (item.hasOverlay) Icon(Icons.Outlined.Layers, "Overlay", tint = SnapVaultColors.info, modifier = Modifier.size(11.dp))
                    }
                }
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LibraryFilterTabs(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(3.dp)
    ) {
        listOf("All", "Photos", "Videos").forEach { filter ->
            val active = selected == filter
            val label = when (filter) {
                "All" -> stringResource(Res.string.lib_filter_all)
                "Photos" -> stringResource(Res.string.lib_filter_photos)
                "Videos" -> stringResource(Res.string.lib_filter_videos)
                else -> filter
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(32.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Outlined.Search,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(13.dp)
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            // BasicTextField takes a whole TextStyle rather than a style + overrides, so
            // the role is merged rather than substituted.
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        stringResource(Res.string.lib_search_placeholder),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                inner()
            }
        )
    }
}

private fun formatBytes(bytes: Long): String {
    fun oneDecimal(value: Double): String {
        val tenths = (value * 10 + 0.5).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }
    return when {
        bytes >= 1_073_741_824L -> "${oneDecimal(bytes / 1_073_741_824.0)} GB"
        bytes >= 1_048_576L     -> "${oneDecimal(bytes / 1_048_576.0)} MB"
        bytes >= 1_024L         -> "${bytes / 1_024L} KB"
        else                    -> "$bytes B"
    }
}
