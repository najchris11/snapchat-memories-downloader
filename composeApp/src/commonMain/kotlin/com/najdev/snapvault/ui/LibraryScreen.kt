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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    val type: String, // "photo" or "video"
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

    Row(modifier = Modifier.fillMaxSize()) {
        // Main Library Content
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Filters bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Type selector
                    Row(
                        modifier = Modifier
                            .background(SurfaceContainerLowest, RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(4.dp)
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
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (active) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                                    .clickable { selectedFilter = filter }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    color = if (active) ElectricPurple else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.1f)))

                    // Date range picker trigger
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .clickable { }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Text("Filter by Date", fontSize = 12.sp)
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier
                            .background(SurfaceContainerLowest, RoundedCornerShape(6.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                    ) {
                        Box(Modifier.clickable {}.padding(6.dp)) {
                            Icon(Icons.Default.List, contentDescription = null, tint = ElectricPurple, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text("Sort: Newest First", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Grid items
            val filteredItems = when (selectedFilter) {
                "Photos" -> items.filter { it.type == "photo" }
                "Videos" -> items.filter { it.type == "video" }
                else -> items
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredItems) { item ->
                    MediaCard(item)
                }
            }
        }

        // Right Inspector Panel
        Card(
            modifier = Modifier.width(300.dp).fillMaxHeight(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(0.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Text("Inspector", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                // Storage usage gauge
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("STORAGE USAGE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("84% Full", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ElectricPurple)
                    }

                    LinearProgressIndicator(
                        progress = 0.84f,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(100)),
                        color = ElectricPurple,
                        trackColor = Color.White.copy(alpha = 0.05f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("42.1 GB used", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Text("50 GB total", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }

                // Metadata Status
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("METADATA EXTRACTION", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ElectricPurple, modifier = Modifier.size(18.dp))
                        Column {
                            Text("GPS Data Verified", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("214 items updated", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Build, contentDescription = null, tint = InfoBlue, modifier = Modifier.size(18.dp))
                        Column {
                            Text("Overlay Detected", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("12 new assets combined", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Vault Tools
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("VAULT TOOLS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolButton(icon = Icons.Default.ArrowBack, label = "Export", modifier = Modifier.weight(1f))
                        ToolButton(icon = Icons.Default.Refresh, label = "Optimize", modifier = Modifier.weight(1f))
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolButton(icon = Icons.Default.Lock, label = "Privacy", modifier = Modifier.weight(1f))
                        ToolButton(icon = Icons.Default.Share, label = "Vault Link", modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable { },
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerHigh.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
            // Thumbnail box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .background(SurfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                // Mock thumbnail overlay colors
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                            )
                        )
                )

                Icon(
                    imageVector = if (isVideo) Icons.Default.PlayArrow else Icons.Default.Home,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.15f),
                    modifier = Modifier.size(48.dp)
                )

                // Type badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(100))
                        .background(
                            if (isVideo) InfoBlue.copy(alpha = 0.2f)
                            else ElectricPurple.copy(alpha = 0.2f)
                        )
                        .border(
                            1.dp,
                            if (isVideo) InfoBlue.copy(alpha = 0.2f) else ElectricPurple.copy(alpha = 0.2f),
                            RoundedCornerShape(100)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.type.uppercase(),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isVideo) InfoBlue else ElectricPurple
                    )
                }

                // Video play overlay
                if (isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.4f))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(4.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }

                // Video duration
                if (item.duration != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(item.duration, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                    }
                }
            }

            // Info box
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.date, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.hasGps) {
                            Icon(Icons.Default.LocationOn, contentDescription = "GPS Active", tint = ElectricPurple, modifier = Modifier.size(12.dp))
                        }
                        if (item.hasOverlay) {
                            Icon(Icons.Default.Build, contentDescription = "Overlay Active", tint = InfoBlue, modifier = Modifier.size(12.dp))
                        }
                        if (item.favorited) {
                            Icon(Icons.Default.Favorite, contentDescription = "Favorite", tint = ElectricPurple, modifier = Modifier.size(12.dp))
                        }
                    }
                }

                Text(
                    text = item.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
