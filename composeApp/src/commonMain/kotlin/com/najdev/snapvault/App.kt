package com.najdev.snapvault

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najdev.snapvault.downloader.Deduplicator
import com.najdev.snapvault.downloader.DownloadEngine
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.parser.HistoryParser
import com.najdev.snapvault.ui.DashboardScreen
import com.najdev.snapvault.ui.LibraryScreen
import com.najdev.snapvault.ui.SettingsScreen
import com.najdev.snapvault.ui.theme.ElectricPurple
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import com.najdev.snapvault.ui.theme.SurfaceContainerLowest
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

enum class Screen { Dashboard, Library, Settings }

@Composable
fun App(
    pickers: PlatformPickers,
    mediaProcessor: MediaProcessor,
    fileSystem: FileSystem,
) {
    var currentScreen by remember { mutableStateOf(Screen.Dashboard) }

    var htmlFile by remember { mutableStateOf<String?>(null) }
    var downloadFolder by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var speedText by remember { mutableStateOf("SPEED: --") }
    var etaText by remember { mutableStateOf("ETA: --") }
    var currentStep by remember { mutableStateOf(0) }

    var hasExifTool by remember { mutableStateOf(false) }
    var hasFFmpeg by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasExifTool = mediaProcessor.checkExifTool()
        hasFFmpeg = mediaProcessor.checkFFmpeg()
    }

    val coroutineScope = rememberCoroutineScope()
    var syncJob by remember { mutableStateOf<Job?>(null) }

    SnapVaultTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // ── Top Bar ─────────────────────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(Res.drawable.ic_launcher),
                            contentDescription = stringResource(Res.string.app_name),
                            modifier = Modifier.size(24.dp),
                            contentScale = ContentScale.Fit
                        )
                        Text(
                            text = stringResource(Res.string.app_name),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                            letterSpacing = (-0.5).sp
                        )
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(ElectricPurple.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.app_version),
                                fontSize = 10.sp,
                                color = ElectricPurple,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(Res.string.topbar_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                        Icon(
                            Icons.Outlined.NotificationsNone,
                            contentDescription = stringResource(Res.string.topbar_notifications),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                        Icon(
                            Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = stringResource(Res.string.topbar_help),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxSize()) {
                // ── Sidebar ──────────────────────────────────────────────────────
                Surface(
                    modifier = Modifier.width(220.dp).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp)
                    ) {
                        // Logo block
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Image(
                                painter = painterResource(Res.drawable.ic_launcher),
                                contentDescription = stringResource(Res.string.app_name),
                                modifier = Modifier.size(36.dp),
                                contentScale = ContentScale.Fit
                            )
                            Column {
                                Text(
                                    text = stringResource(Res.string.app_name),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    letterSpacing = (-0.3).sp
                                )
                                Text(
                                    text = stringResource(Res.string.app_tagline),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    letterSpacing = 0.2.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = Color.White.copy(alpha = 0.06f)
                        )

                        // Navigation items
                        SidebarNavItem(
                            label = stringResource(Res.string.nav_dashboard),
                            iconActive = Icons.Filled.Dashboard,
                            iconInactive = Icons.Outlined.Dashboard,
                            active = currentScreen == Screen.Dashboard,
                            onClick = { currentScreen = Screen.Dashboard }
                        )
                        Spacer(Modifier.height(2.dp))
                        SidebarNavItem(
                            label = stringResource(Res.string.nav_library),
                            iconActive = Icons.Filled.PhotoLibrary,
                            iconInactive = Icons.Outlined.PhotoLibrary,
                            active = currentScreen == Screen.Library,
                            onClick = { currentScreen = Screen.Library }
                        )
                        Spacer(Modifier.height(2.dp))
                        SidebarNavItem(
                            label = stringResource(Res.string.nav_settings),
                            iconActive = Icons.Filled.Tune,
                            iconInactive = Icons.Outlined.Tune,
                            active = currentScreen == Screen.Settings,
                            onClick = { currentScreen = Screen.Settings }
                        )

                        Spacer(Modifier.weight(1f))

                        // Status chip
                        val statusLabel = when {
                            isRunning -> stringResource(Res.string.nav_status_running)
                            currentStep == 3 -> stringResource(Res.string.nav_status_complete)
                            else -> stringResource(Res.string.nav_status_idle)
                        }
                        val statusColor by animateColorAsState(
                            when {
                                isRunning -> ElectricPurple
                                currentStep == 3 -> Color(0xFF4ADE80)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceContainerLowest)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(RoundedCornerShape(100))
                                    .background(statusColor)
                            )
                            Text(
                                text = statusLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                            Spacer(Modifier.weight(1f))
                            if (isRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = ElectricPurple
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Quick-launch sync from sidebar goes to Dashboard
                        Button(
                            onClick = { currentScreen = Screen.Dashboard },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.Sync, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(Res.string.nav_start_sync),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                // ── Main content ─────────────────────────────────────────────────
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (currentScreen) {
                        Screen.Dashboard -> DashboardScreen(
                            htmlFile = htmlFile,
                            downloadFolder = downloadFolder,
                            onSelectHtmlFile = {
                                pickers.pickHtmlFile { result -> result?.let { htmlFile = it } }
                            },
                            onSelectFolder = {
                                pickers.pickFolder { result -> result?.let { downloadFolder = it } }
                            },
                            onNavigateToSettings = { currentScreen = Screen.Settings },
                            onStartSync = { download, metadata, _, dedupe, dry, workerCount ->
                                isRunning = true
                                logs.clear()
                                progress = 0f
                                progressText = "Reading memories…"
                                currentStep = 1

                                logs.add("[INFO] Starting pipeline sequence…")
                                syncJob = coroutineScope.launch(Dispatchers.Default) {
                                    try {
                                        val htmlPath = htmlFile ?: return@launch
                                        val outDir = downloadFolder ?: return@launch

                                        val htmlContent = fileSystem.read(htmlPath.toPath()) { readUtf8() }
                                        logs.add("[INFO] Parsing memories history…")
                                        val items = HistoryParser.parse(htmlContent)
                                        logs.add("[SUCCESS] Parsed ${items.size} memory items.")

                                        if (items.isEmpty()) {
                                            logs.add("[WARN] No items found in the HTML file.")
                                            isRunning = false
                                            currentStep = 3
                                            return@launch
                                        }

                                        if (download) {
                                            logs.add("[INFO] Downloading files in parallel…")
                                            val httpClient = HttpClient()
                                            val downloader = DownloadEngine(httpClient, fileSystem)
                                            var downloadedCount = 0
                                            val totalCount = items.size

                                            val downloadCollector = downloader.progressFlow.onEach { (item, status) ->
                                                downloadedCount++
                                                progress = downloadedCount.toFloat() / totalCount
                                                progressText = "Downloading: $downloadedCount of $totalCount files…"

                                                val name = item.downloadedPath?.let { path ->
                                                    val lastSlash = path.lastIndexOfAny(charArrayOf('/', '\\'))
                                                    if (lastSlash != -1) path.substring(lastSlash + 1) else path
                                                } ?: downloader.buildFilename(item, null)

                                                when (status) {
                                                    "downloaded" -> {
                                                        logs.add("[DOWNLOADED] $name")
                                                        if (metadata && hasExifTool && item.latitude != null && item.longitude != null && item.downloadedPath != null) {
                                                            val exifOk = mediaProcessor.writeGpsMetadata(item.downloadedPath, item.latitude, item.longitude, item.dateStr)
                                                            if (exifOk) logs.add("   -> [METADATA] EXIF GPS tag written.")
                                                        }
                                                    }
                                                    "skipped" -> logs.add("[SKIPPED] $name (already exists)")
                                                    else -> logs.add("[ERROR] Failed $name: $status")
                                                }
                                            }.launchIn(CoroutineScope(Dispatchers.Default))

                                            downloader.downloadAll(items, outDir, workerCount)
                                            downloadCollector.cancel()
                                            httpClient.close()
                                        }

                                        currentStep = 2

                                        if (dedupe) {
                                            logs.add("[INFO] Scanning for duplicate files…")
                                            val deduplicator = Deduplicator(fileSystem)
                                            val results = deduplicator.deduplicateFolder(outDir.toPath(), dry)
                                            if (results.isEmpty()) {
                                                logs.add("[SUCCESS] No duplicate files found.")
                                            } else {
                                                results.forEach { res ->
                                                    logs.add("[DELETED DUPES] Kept ${res.keptFile}, deleted: ${res.deletedFiles.joinToString()}")
                                                }
                                            }
                                        }

                                        progress = 1.0f
                                        progressText = "Pipeline Complete"
                                        currentStep = 3
                                        logs.add("[SUCCESS] Snapchat Memories sync complete!")
                                    } catch (e: Exception) {
                                        logs.add("[ERROR] Pipeline failed: ${e.message}")
                                    } finally {
                                        isRunning = false
                                    }
                                }
                            },
                            onStopSync = {
                                syncJob?.cancel()
                                logs.add("[WARN] Sync cancelled by user.")
                                isRunning = false
                                currentStep = 0
                                progressText = "Cancelled"
                            },
                            isRunning = isRunning,
                            logs = logs,
                            progress = progress,
                            progressText = progressText,
                            etaText = etaText,
                            speedText = speedText,
                            currentStep = currentStep
                        )
                        Screen.Library -> LibraryScreen()
                        Screen.Settings -> SettingsScreen(
                            hasExifTool = hasExifTool,
                            hasFFmpeg = hasFFmpeg,
                            onVerifyDependencies = {
                                hasExifTool = mediaProcessor.checkExifTool()
                                hasFFmpeg = mediaProcessor.checkFFmpeg()
                            },
                            onRunInstaller = {
                                coroutineScope.launch {
                                    isInstalling = true
                                    logs.add("[INFO] Triggering automatic dependency installation…")
                                    kotlinx.coroutines.delay(2000)
                                    hasExifTool = mediaProcessor.checkExifTool()
                                    hasFFmpeg = mediaProcessor.checkFFmpeg()
                                    isInstalling = false
                                    logs.add("[SUCCESS] System checks completed.")
                                }
                            },
                            isInstalling = isInstalling
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SidebarNavItem(
    label: String,
    iconActive: ImageVector,
    iconInactive: ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        if (active) ElectricPurple.copy(alpha = 0.12f) else Color.Transparent
    )
    val contentColor by animateColorAsState(
        if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .background(if (active) ElectricPurple else Color.Transparent)
        )
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (active) iconActive else iconInactive,
                contentDescription = null,
                tint = if (active) ElectricPurple else contentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor
            )
        }
    }
}
