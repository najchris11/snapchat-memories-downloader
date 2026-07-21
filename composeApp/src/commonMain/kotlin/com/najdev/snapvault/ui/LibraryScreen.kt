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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.najdev.snapvault.LocalWindowSize
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.ioDispatcher
import com.najdev.snapvault.getCachedThumbnail
import com.najdev.snapvault.scanMediaFiles
import com.najdev.snapvault.ui.theme.ElectricPurple
import com.najdev.snapvault.ui.theme.InfoBlue
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
    onOpenFolder: () -> Unit
) {
    var refreshKey by remember { mutableStateOf(0) }
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

    val windowSize = LocalWindowSize.current
    val isCompact = windowSize == WindowSize.Compact

    Row(modifier = Modifier.fillMaxSize()) {
        // ── Main content ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(if (isCompact) 14.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip(
                        icon = Icons.Outlined.PhotoLibrary,
                        label = "${items.size} Memories",
                        tint = SnapVaultColors.electricPurple
                    )
                    if (!isCompact) {
                        StatChip(
                            icon = Icons.Outlined.Image,
                            label = "${items.count { it.type == "photo" }} Photos",
                            tint = SnapVaultColors.electricPurple
                        )
                        StatChip(
                            icon = Icons.Outlined.Videocam,
                            label = "${items.count { it.type == "video" }} Videos",
                            tint = SnapVaultColors.info
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

            // Filter + search bar
            if (isCompact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        FilterTab("All", selectedFilter == "All") { selectedFilter = "All" }
                        FilterTab("Photos", selectedFilter == "Photos") { selectedFilter = "Photos" }
                        FilterTab("Videos", selectedFilter == "Videos") { selectedFilter = "Videos" }
                    }

                    // Search field
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            cursorBrush = SolidColor(SnapVaultColors.electricPurple),
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text("Search memories…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                }
                                innerTextField()
                            }
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            FilterTab("All", selectedFilter == "All") { selectedFilter = "All" }
                            FilterTab("Photos", selectedFilter == "Photos") { selectedFilter = "Photos" }
                            FilterTab("Videos", selectedFilter == "Videos") { selectedFilter = "Videos" }
                        }

                        Row(
                            modifier = Modifier
                                .width(200.dp)
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                cursorBrush = SolidColor(SnapVaultColors.electricPurple),
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text("Search memories…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                                    }
                                    innerTextField()
                                }
                            )
                        }
                    }
                }
            }

            // Media grid or empty state
            if (downloadFolder == null) {
                EmptyLibraryState(
                    icon = Icons.Outlined.FolderOpen,
                    title = "No Vault Directory Selected",
                    subtitle = "Select an output directory on the Dashboard or click below to inspect memories.",
                    actionLabel = "Select Folder",
                    onAction = onOpenFolder
                )
            } else if (filteredItems.isEmpty()) {
                EmptyLibraryState(
                    icon = Icons.Outlined.PhotoLibrary,
                    title = if (searchQuery.isNotBlank()) "No Matching Memories" else "Library is Empty",
                    subtitle = if (searchQuery.isNotBlank()) "Try clearing your search query." else "Run a sync on the Dashboard to populate your memory vault.",
                    actionLabel = null,
                    onAction = {}
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(if (isCompact) 110.dp else 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        MediaCard(
                            item = item,
                            selected = selectedItem?.id == item.id,
                            onClick = {
                                selectedItem = item
                                showPreview = true
                            }
                        )
                    }
                }
            }
        }

        // Inspector side pane (Desktop / Expanded only)
        if (!isCompact) {
            Surface(
                modifier = Modifier.width(280.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                InspectorPanel(
                    selectedItem = selectedItem,
                    items = items,
                    onOpenPreview = { showPreview = true }
                )
            }
        }
    }

    // Media preview dialog (shown on card tap for all size classes)
    if (showPreview && selectedItem != null) {
        MediaPreviewDialog(
            item = selectedItem!!,
            onDismiss = { showPreview = false }
        )
    }
}

@Composable
private fun FilterTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) SnapVaultColors.electricPurple.copy(alpha = 0.15f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) SnapVaultColors.electricPurple else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyLibraryState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
            }
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (actionLabel != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = SnapVaultColors.electricPurple)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun InspectorPanel(
    selectedItem: LibraryItem?,
    items: List<LibraryItem>,
    onOpenPreview: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(Res.string.lib_inspector_title), fontSize = 14.sp, fontWeight = FontWeight.Bold)

        if (selectedItem != null) {
            InspectorItemDetail(item = selectedItem, onOpenPreview = onOpenPreview)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Select an item to view metadata details",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

        val totalBytes = items.sumOf { it.fileSizeBytes }
        val gpsCount = items.count { it.hasGps }
        val overlayCount = items.count { it.hasOverlay }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(Icons.Outlined.Storage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(11.dp))
                Text(stringResource(Res.string.lib_storage_label), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (items.isEmpty()) "—" else "${items.size} file${if (items.size == 1) "" else "s"}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    if (items.isEmpty()) "—" else formatBytes(totalBytes),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = SnapVaultColors.electricPurple
                )
            }
            if (items.isNotEmpty()) {
                val photoCount = items.count { it.type == "photo" }
                val videoCount = items.count { it.type == "video" }
                Text(
                    "$photoCount photo${if (photoCount == 1) "" else "s"} · $videoCount video${if (videoCount == 1) "" else "s"}",
                    fontSize = 10.sp,
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
                Text(stringResource(Res.string.lib_metadata_label), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
            }
            MetadataRow(
                icon = Icons.Outlined.GpsFixed,
                iconTint = SnapVaultColors.electricPurple,
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
private fun InspectorItemDetail(item: LibraryItem, onOpenPreview: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onOpenPreview() }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Text(item.title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.date, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.fileSizeBytes > 0) {
            Text(formatBytes(item.fileSizeBytes), fontSize = 11.sp, color = SnapVaultColors.electricPurple, fontWeight = FontWeight.Bold)
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
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = tint)
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
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPreviewDialog(
    item: LibraryItem,
    onDismiss: () -> Unit
) {
    val isVideo = item.type == "video"

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (isVideo) {
                    VideoPlayer(
                        videoPath = item.id,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val fullImage by produceState<ImageBitmap?>(null, item.id) {
                        value = withContext(ioDispatcher) { com.najdev.snapvault.loadFullImage(item.id) }
                    }

                    if (fullImage != null) {
                        Image(
                            bitmap = fullImage!!,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = SnapVaultColors.electricPurple)
                        }
                    }
                }

                // Close button
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(44.dp)
                        .clip(RoundedCornerShape(100))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, "Close", tint = Color.White, modifier = Modifier.size(24.dp))
                }

                // Bottom metadata info bar
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    color = Color.Black.copy(alpha = 0.75f)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(item.date, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                            if (item.fileSizeBytes > 0) {
                                Text(formatBytes(item.fileSizeBytes), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SnapVaultColors.electricPurple)
                            }
                            if (item.hasGps) {
                                Text("GPS VERIFIED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SnapVaultColors.success)
                            }
                            if (item.hasOverlay) {
                                Text("OVERLAY COMBINED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SnapVaultColors.info)
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
            if (selected) SnapVaultColors.electricPurple else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column {
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
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
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

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(100))
                        .background(if (isVideo) SnapVaultColors.info.copy(alpha = 0.2f) else SnapVaultColors.electricPurple.copy(alpha = 0.2f))
                        .padding(4.dp)
                ) {
                    Icon(
                        if (isVideo) Icons.Outlined.Videocam else Icons.Outlined.Image,
                        contentDescription = null,
                        tint = if (isVideo) SnapVaultColors.info else SnapVaultColors.electricPurple,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${(kb * 10).toInt() / 10.0} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${(mb * 10).toInt() / 10.0} MB"
    val gb = mb / 1024.0
    return "${(gb * 10).toInt() / 10.0} GB"
}
