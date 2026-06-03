package com.najdev.snapvault.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najdev.snapvault.ui.theme.ElectricPurple
import com.najdev.snapvault.ui.theme.InfoBlue
import com.najdev.snapvault.ui.theme.SurfaceContainerHigh
import com.najdev.snapvault.ui.theme.SurfaceContainerLowest
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
    val favorited: Boolean = false
)

@Composable
fun LibraryScreen() {
    val items = remember {
        listOf(
            LibraryItem("1", "OCT 12, 2023", "Mountain Peak Sunset", "photo", null, true, true),
            LibraryItem("2", "SEPT 28, 2023", "Midnight City Walk", "video", "00:15", false, false),
            LibraryItem("3", "AUG 15, 2023", "Abstract Flows #04", "photo", null, false, false, true),
            LibraryItem("4", "JUL 22, 2023", "Alpine Expedition", "photo", null, true, false),
            LibraryItem("5", "JUN 08, 2023", "Tech Rig Showcase", "video", "00:42", false, true),
            LibraryItem("6", "MAY 19, 2023", "Nebula Dreams", "photo", null, true, false),
            LibraryItem("7", "APR 30, 2023", "Minimalist Spheres", "photo", null, false, false),
            LibraryItem("8", "MAR 12, 2023", "Global Sync Demo", "video", "01:05", true, false)
        )
    }

    var selectedFilter by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredItems = remember(selectedFilter, searchQuery) {
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
            modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatChip(
                    icon = Icons.Outlined.PhotoLibrary,
                    label = "${items.size} Memories",
                    tint = ElectricPurple
                )
                StatChip(
                    icon = Icons.Outlined.Image,
                    label = "${items.count { it.type == "photo" }} Photos",
                    tint = ElectricPurple.copy(alpha = 0.7f)
                )
                StatChip(
                    icon = Icons.Outlined.Videocam,
                    label = "${items.count { it.type == "video" }} Videos",
                    tint = InfoBlue
                )
                StatChip(
                    icon = Icons.Outlined.GpsFixed,
                    label = "${items.count { it.hasGps }} with GPS",
                    tint = Color(0xFF4ADE80)
                )
            }

            // Filter + search bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Type filter tabs
                    Row(
                        modifier = Modifier
                            .background(SurfaceContainerLowest, RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(3.dp)
                    ) {
                        listOf("All", "Photos", "Videos").forEach { filter ->
                            val active = selectedFilter == filter
                            val label = when (filter) {
                                "All" -> stringResource(Res.string.lib_filter_all)
                                "Photos" -> stringResource(Res.string.lib_filter_photos)
                                "Videos" -> stringResource(Res.string.lib_filter_videos)
                                else -> filter
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(if (active) ElectricPurple.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable { selectedFilter = filter }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (active) ElectricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    // Date filter
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceContainerLowest)
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .clickable { }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Outlined.DateRange,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            stringResource(Res.string.lib_filter_date),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Search field
                    Row(
                        modifier = Modifier
                            .width(200.dp)
                            .height(32.dp)
                            .background(SurfaceContainerLowest, RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(13.dp)
                        )
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp
                            ),
                            cursorBrush = SolidColor(ElectricPurple),
                            decorationBox = { inner ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        stringResource(Res.string.lib_search_placeholder),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                    )
                                }
                                inner()
                            }
                        )
                    }

                    // Sort label
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceContainerLowest)
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.Sort,
                            null,
                            tint = ElectricPurple,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            stringResource(Res.string.lib_sort_newest),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            stringResource(Res.string.lib_empty_state),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.widthIn(max = 280.dp)
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredItems) { item -> MediaCard(item) }
                }
            }
        }

        // ── Inspector panel ──────────────────────────────────────────────────
        Surface(
            modifier = Modifier.width(280.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            shape = RoundedCornerShape(0.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        null,
                        tint = ElectricPurple,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        stringResource(Res.string.lib_inspector_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Storage usage
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Storage,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                stringResource(Res.string.lib_storage_label),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        Text("84%", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ElectricPurple)
                    }
                    LinearProgressIndicator(
                        progress = { 0.84f },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(100)),
                        color = ElectricPurple,
                        trackColor = Color.White.copy(alpha = 0.05f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("42.1 GB used", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Text("50 GB total", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                // Metadata extraction
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Tag,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            stringResource(Res.string.lib_metadata_label),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }

                    MetadataRow(
                        icon = Icons.Outlined.GpsFixed,
                        iconTint = ElectricPurple,
                        title = stringResource(Res.string.lib_gps_verified),
                        subtitle = "214 items updated"
                    )
                    MetadataRow(
                        icon = Icons.Outlined.Layers,
                        iconTint = InfoBlue,
                        title = stringResource(Res.string.lib_overlay_detected),
                        subtitle = "12 assets combined"
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                // Vault tools
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Build,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            stringResource(Res.string.lib_vault_tools_label),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolButton(Icons.Outlined.IosShare, stringResource(Res.string.lib_tool_export), Modifier.weight(1f))
                        ToolButton(Icons.Outlined.AutoAwesome, stringResource(Res.string.lib_tool_optimize), Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolButton(Icons.Outlined.VisibilityOff, stringResource(Res.string.lib_tool_privacy), Modifier.weight(1f))
                        ToolButton(Icons.Outlined.Link, stringResource(Res.string.lib_tool_vault_link), Modifier.weight(1f))
                    }
                }
            }
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
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable { },
        color = SurfaceContainerHigh.copy(alpha = 0.35f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun MediaCard(item: LibraryItem) {
    val isVideo = item.type == "video"

    Card(
        modifier = Modifier.fillMaxWidth().clickable { },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Column {
            // Thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .background(SurfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                            )
                        )
                )

                Icon(
                    imageVector = if (isVideo) Icons.Outlined.PlayCircle else Icons.Outlined.Image,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier.size(44.dp)
                )

                // Type badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(100))
                        .background(if (isVideo) InfoBlue.copy(alpha = 0.2f) else ElectricPurple.copy(alpha = 0.2f))
                        .border(
                            1.dp,
                            if (isVideo) InfoBlue.copy(alpha = 0.3f) else ElectricPurple.copy(alpha = 0.3f),
                            RoundedCornerShape(100)
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.type.uppercase(),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isVideo) InfoBlue else ElectricPurple
                    )
                }

                // Duration badge
                if (item.duration != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            item.duration,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
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
                            tint = ElectricPurple,
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
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.hasGps) Icon(Icons.Outlined.GpsFixed, "GPS", tint = ElectricPurple, modifier = Modifier.size(11.dp))
                        if (item.hasOverlay) Icon(Icons.Outlined.Layers, "Overlay", tint = InfoBlue, modifier = Modifier.size(11.dp))
                    }
                }
                Text(
                    item.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
