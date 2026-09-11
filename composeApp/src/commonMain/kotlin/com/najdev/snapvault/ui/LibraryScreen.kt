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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
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
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

/**
 * Which media the Library is showing.
 *
 * This was a `String` holding "All", "Photos" or "Videos", with those literals driving the
 * predicate, the tab list and the resource lookup independently — three copies of one fact,
 * held together by nothing. [matches] is the predicate; [label] is what it is called on
 * screen; neither can drift from the other now.
 */
enum class MediaFilter {
    All,
    Photos,
    Videos;

    fun matches(item: LibraryItem): Boolean = when (this) {
        All -> true
        Photos -> item.type == "photo"
        Videos -> item.type == "video"
    }
}

@Composable
internal fun MediaFilter.label(): String = when (this) {
    MediaFilter.All -> stringResource(Res.string.lib_filter_all)
    MediaFilter.Photos -> stringResource(Res.string.lib_filter_photos)
    MediaFilter.Videos -> stringResource(Res.string.lib_filter_videos)
}

// Typographic, not copy: an em dash standing in for a statistic that has no value yet, and
// the separator between two counts on one line. Neither is translated text.
private const val EMPTY_STAT = "—"
private const val STAT_SEPARATOR = " · "

data class LibraryItem(
    val id: String,
    val date: String,
    val title: String,
    val type: String,
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

    var selectedFilter by remember { mutableStateOf(MediaFilter.All) }
    var searchQuery by remember { mutableStateOf("") }
    // An index rather than the item itself: the keyboard moves the selection by position,
    // and "the item after this one" is not a question a LibraryItem can answer.
    var selectedIndex by remember { mutableStateOf(LIBRARY_NO_SELECTION) }
    var showPreview by remember { mutableStateOf(false) }

    val gridFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }

    val filteredItems = remember(items, selectedFilter, searchQuery) {
        items
            .filter(selectedFilter::matches)
            .filter { item ->
                searchQuery.isBlank() || item.title.contains(searchQuery, ignoreCase = true)
            }
    }
    val selectedItem = filteredItems.getOrNull(selectedIndex)

    // Filtering re-indexes everything, so a held index would point at a different memory —
    // or past the end. Dropping it is the only honest answer.
    LaunchedEffect(filteredItems) { selectedIndex = LIBRARY_NO_SELECTION }

    // Counted once rather than rescanned inside each chip's label.
    val photoCount = items.count { it.type == "photo" }
    val videoCount = items.count { it.type == "video" }
    val gpsCount = items.count { it.hasGps }

    Row(modifier = Modifier.fillMaxSize().focusSearchOnSlash(searchFocus) { searchFocused }) {
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
                        label = pluralStringResource(Res.plurals.lib_memory_count, items.size, items.size),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    StatChip(
                        icon = Icons.Outlined.Image,
                        label = photoCount.let { pluralStringResource(Res.plurals.lib_photo_count, it, it) },
                        tint = MaterialTheme.colorScheme.primary
                    )
                    StatChip(
                        icon = Icons.Outlined.Videocam,
                        label = videoCount.let { pluralStringResource(Res.plurals.lib_video_count, it, it) },
                        tint = SnapVaultColors.info
                    )
                    StatChip(
                        icon = Icons.Outlined.GpsFixed,
                        label = gpsCount.let { pluralStringResource(Res.plurals.lib_gps_chip_count, it, it) },
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
                    IconButton(onClick = { refreshKey++ }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(Res.string.lib_refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        focusRequester = searchFocus,
                        onFocusChanged = { searchFocused = it },
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
                        focusRequester = searchFocus,
                        onFocusChanged = { searchFocused = it },
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.widthIn(max = 280.dp)
                        )
                        if (downloadFolder == null) {
                            TextButton(onClick = onOpenFolder) {
                                Text(
                                    stringResource(Res.string.lib_select_folder),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            } else {
                LibraryGrid(
                    items = filteredItems,
                    selectedIndex = selectedIndex,
                    onSelect = { selectedIndex = it },
                    onOpen = { selectedIndex = it; showPreview = true },
                    compact = compact,
                    focusRequester = gridFocus,
                    modifier = Modifier.weight(1f),
                )
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
                color = MaterialTheme.colorScheme.surfaceContainerLow,
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
                            onClearSelection = { selectedIndex = LIBRARY_NO_SELECTION }
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
                .clickable(role = Role.Button) { onPreview() },
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
                    stringResource(Res.string.lib_clear_selection),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Metadata rows
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InspectorDetailRow(
                    label = stringResource(Res.string.lib_detail_size),
                    value = if (item.fileSizeBytes > 0) formatBytes(item.fileSizeBytes) else EMPTY_STAT,
                    valueColor = MaterialTheme.colorScheme.primary
                )
                InspectorDetailRow(
                    label = stringResource(Res.string.lib_detail_gps),
                    value = stringResource(if (item.hasGps) Res.string.lib_gps_tagged else Res.string.lib_gps_none),
                    valueColor = if (item.hasGps) SnapVaultColors.success else MaterialTheme.colorScheme.onSurfaceVariant
                )
                InspectorDetailRow(
                    label = stringResource(Res.string.lib_detail_overlay),
                    value = stringResource(if (item.hasOverlay) Res.string.lib_overlay_combined else Res.string.lib_overlay_none),
                    valueColor = if (item.hasOverlay) SnapVaultColors.info else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Icon(Icons.Outlined.Storage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(11.dp))
                Text(stringResource(Res.string.lib_storage_label), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (items.isEmpty()) EMPTY_STAT else pluralStringResource(Res.plurals.lib_file_count, items.size, items.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (items.isEmpty()) EMPTY_STAT else formatBytes(totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (items.isNotEmpty()) {
                val photoCount = items.count { it.type == "photo" }
                val videoCount = items.count { it.type == "video" }
                Text(
                    pluralStringResource(Res.plurals.lib_photo_count, photoCount, photoCount) +
                        STAT_SEPARATOR +
                        pluralStringResource(Res.plurals.lib_video_count, videoCount, videoCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(Icons.Outlined.Tag, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(11.dp))
                Text(stringResource(Res.string.lib_metadata_label), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MetadataRow(
                icon = Icons.Outlined.GpsFixed,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(Res.string.lib_gps_verified),
                subtitle = if (items.isEmpty()) EMPTY_STAT else pluralStringResource(Res.plurals.lib_tagged_count, gpsCount, gpsCount)
            )
            MetadataRow(
                icon = Icons.Outlined.Layers,
                iconTint = SnapVaultColors.info,
                title = stringResource(Res.string.lib_overlay_detected),
                subtitle = if (items.isEmpty()) EMPTY_STAT else pluralStringResource(Res.plurals.lib_asset_count, overlayCount, overlayCount)
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
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

    // The dialog covers the whole window, so Escape has to work: without it the only exits
    // are a click on the scrim or on the close button in the corner.
    //
    // The scrim takes focus on open, and that is load-bearing rather than tidiness: key
    // events are routed along the focus path, so with nothing in the dialog focused the
    // handler below never fires at all. Removing the focusable was tried; the test caught it.
    val dismissFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { dismissFocus.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MediaColors.scrimDialog)
                .clickable { onDismiss() }
                .focusRequester(dismissFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 860.dp)
                    .heightIn(max = 680.dp)
                    // Consume taps so the scrim's dismiss handler doesn't fire. Deliberately
                    // not clickable { }: that made the whole card focusable, gave it a ripple,
                    // and announced it as a control that does nothing.
                    .pointerInput(Unit) { detectTapGestures { } },
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
                            Text(item.date, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Text(stringResource(Res.string.lib_detail_gps), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
                                    Text(stringResource(Res.string.lib_detail_overlay), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SnapVaultColors.info)
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
        // `clickable` rather than a tap gesture, and that is load-bearing beyond the ripple:
        // it makes the card focusable and takes focus on click, which is the only way arrow
        // navigation ever starts for a mouse user. Swapping it for detectTapGestures fails
        // two tests in LibraryKeyboardTest.
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onClick() },
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.hasGps) Icon(Icons.Outlined.GpsFixed, stringResource(Res.string.lib_detail_gps), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(11.dp))
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

/**
 * The media grid, extracted so its keyboard handling has somewhere to live — and somewhere to
 * be tested, which inline in [LibraryScreen] it did not, since reaching it needed a real
 * folder on disk.
 */
@Composable
internal fun LibraryGrid(
    items: List<LibraryItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onOpen: (Int) -> Unit,
    compact: Boolean,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()

    // The grid is Adaptive, so the column count only exists after layout. Items on one row
    // share a y offset, and counting them is the only way to ask what it came out as.
    val columns by remember(gridState) {
        derivedStateOf {
            val visible = gridState.layoutInfo.visibleItemsInfo
            val topRow = visible.firstOrNull()?.offset?.y ?: return@derivedStateOf 1
            visible.count { it.offset.y == topRow }.coerceAtLeast(1)
        }
    }

    // Walking the selection past the visible rows has to bring it back on screen, or the
    // keyboard moves something the user cannot see.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.index == selectedIndex }) {
            gridState.animateScrollToItem(selectedIndex)
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = if (compact) 130.dp else 160.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val target = libraryGridTarget(event.key, selectedIndex, items.size, columns)
                when {
                    target != null -> { onSelect(target); true }
                    selectedIndex >= 0 && (event.key == Key.Enter || event.key == Key.Spacebar) -> {
                        onOpen(selectedIndex)
                        true
                    }
                    else -> false
                }
            }
    ) {
        itemsIndexed(items) { index, item ->
            MediaCard(
                item = item,
                selected = index == selectedIndex,
                onClick = { onOpen(index) },
            )
        }
    }
}

@Composable
private fun LibraryFilterTabs(
    selected: MediaFilter,
    onSelect: (MediaFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(3.dp)
    ) {
        MediaFilter.entries.forEach { filter ->
            val active = selected == filter
            val label = filter.label()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                    .selectable(
                        selected = active,
                        role = Role.RadioButton,
                        onClick = { onSelect(filter) },
                    )
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) },
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
