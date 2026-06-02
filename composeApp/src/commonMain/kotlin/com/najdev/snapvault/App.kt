package com.najdev.snapvault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najdev.snapvault.downloader.Deduplicator
import com.najdev.snapvault.downloader.DownloadEngine
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.model.MemoryItem
import com.najdev.snapvault.parser.HistoryParser
import com.najdev.snapvault.ui.DashboardScreen
import com.najdev.snapvault.ui.EnvironmentScreen
import com.najdev.snapvault.ui.LibraryScreen
import com.najdev.snapvault.ui.SettingsScreen
import com.najdev.snapvault.ui.theme.ElectricPurple
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import com.najdev.snapvault.ui.theme.SurfaceContainerLowest
import io.ktor.client.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath

import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

enum class Screen {
    Dashboard, Library, Environment, Settings
}

@Composable
fun App(
    pickers: PlatformPickers,
    mediaProcessor: MediaProcessor,
    fileSystem: FileSystem,
) {
    var currentScreen by remember { mutableStateOf(Screen.Dashboard) }

    // State for pipeline
    var htmlFile by remember { mutableStateOf<String?>(null) }
    var downloadFolder by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("Ready to sync") }
    var speedText by remember { mutableStateOf("SPEED: --") }
    var etaText by remember { mutableStateOf("ETA: --") }
    var currentStep by remember { mutableStateOf(0) } // 0: Setup, 1: Syncing, 2: Processing, 3: Complete

    // State for system dependencies
    var hasExifTool by remember { mutableStateOf(false) }
    var hasFFmpeg by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }

    // Run initial dependency checks
    LaunchedEffect(Unit) {
        hasExifTool = mediaProcessor.checkExifTool()
        hasFFmpeg = mediaProcessor.checkFFmpeg()
    }

    val coroutineScope = rememberCoroutineScope()
    var syncJob by remember { mutableStateOf<Job?>(null) }

    SnapVaultTheme {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Sidebar Navigation
            Card(
                modifier = Modifier.width(240.dp).fillMaxHeight(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(0.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // Title Header
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(Res.string.app_name),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Utility",
                            fontSize = 11.sp,
                            color = ElectricPurple,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    // Navigation Links
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NavigationLink(
                            label = stringResource(Res.string.nav_dashboard),
                            icon = Icons.Default.Menu,
                            active = currentScreen == Screen.Dashboard,
                            onClick = { currentScreen = Screen.Dashboard }
                        )
                        NavigationLink(
                            label = stringResource(Res.string.nav_library),
                            icon = Icons.AutoMirrored.Filled.List,
                            active = currentScreen == Screen.Library,
                            onClick = { currentScreen = Screen.Library }
                        )
                        NavigationLink(
                            label = stringResource(Res.string.nav_environment),
                            icon = Icons.Default.Build,
                            active = currentScreen == Screen.Environment,
                            onClick = { currentScreen = Screen.Environment }
                        )
                        NavigationLink(
                            label = stringResource(Res.string.nav_settings),
                            icon = Icons.Default.Settings,
                            active = currentScreen == Screen.Settings,
                            onClick = { currentScreen = Screen.Settings }
                        )
                    }

                    HorizontalDivider(
                        Modifier,
                        DividerDefaults.Thickness,
                        color = Color.White.copy(alpha = 0.05f)
                    )

                    // Profile Card
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(100))
                                .background(ElectricPurple.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("S", fontWeight = FontWeight.Bold, color = ElectricPurple)
                        }
                        Column {
                            Text("SnapVault User", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Local Storage", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }

            // Main view
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (currentScreen) {
                    Screen.Dashboard -> DashboardScreen(
                        htmlFile = htmlFile,
                        downloadFolder = downloadFolder,
                        onSelectHtmlFile = {
                            pickers.pickHtmlFile { result ->
                                result?.let { htmlFile = it }
                            }
                        },
                        onSelectFolder = {
                            pickers.pickFolder { result ->
                                result?.let { downloadFolder = it }
                            }
                        },
                        onStartSync = { download, metadata, _, dedupe, dry, workerCount ->
                            isRunning = true
                            logs.clear()
                            progress = 0f
                            progressText = "Reading memories..."
                            currentStep = 1 // Syncing

                            logs.add("[INFO] Starting pipeline sequence...")
                            syncJob = coroutineScope.launch(Dispatchers.Default) {
                                try {
                                    val htmlPath = htmlFile ?: return@launch
                                    val outDir = downloadFolder ?: return@launch
                                    
                                    val htmlContent = fileSystem.read(htmlPath.toPath()) { readUtf8() }
                                    logs.add("[INFO] Parsing HTML memories history...")
                                    val items = HistoryParser.parse(htmlContent)
                                    logs.add("[SUCCESS] Parsed ${items.size} memory items.")

                                    if (items.isEmpty()) {
                                        logs.add("[WARN] No items found in the HTML file.")
                                        isRunning = false
                                        currentStep = 3 // Complete
                                        return@launch
                                    }

                                    if (download) {
                                        logs.add("[INFO] Downloading files in parallel...")
                                        val httpClient = HttpClient()
                                        val downloader = DownloadEngine(httpClient, fileSystem)
                                        
                                        var downloadedCount = 0
                                        val totalCount = items.size
                                        
                                        // Collect progress updates
                                        val downloadCollector = downloader.progressFlow.onEach { (item, status) ->
                                            downloadedCount++
                                            progress = downloadedCount.toFloat() / totalCount
                                            progressText = "Downloading: $downloadedCount of $totalCount files..."
                                            
                                            val name = item.downloadedPath?.let { path ->
                                                val lastSlash = path.lastIndexOfAny(charArrayOf('/', '\\'))
                                                if (lastSlash != -1) path.substring(lastSlash + 1) else path
                                            } ?: downloader.buildFilename(item, null)
                                            when (status) {
                                                "downloaded" -> {
                                                    logs.add("[DOWNLOADED] $name")
                                                    
                                                    // GPS writing on the fly
                                                    if (metadata && hasExifTool && item.latitude != null && item.longitude != null && item.downloadedPath != null) {
                                                        val exifOk = mediaProcessor.writeGpsMetadata(item.downloadedPath, item.latitude, item.longitude, item.dateStr)
                                                        if (exifOk) {
                                                            logs.add("   -> [METADATA] EXIF GPS tag written.")
                                                        }
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

                                    currentStep = 2 // Processing

                                    if (dedupe) {
                                        logs.add("[INFO] Scanning for duplicate files...")
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
                                    currentStep = 3 // Complete
                                    logs.add("[SUCCESS] Snapchat Memories Modernization complete!")
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
                    Screen.Environment -> EnvironmentScreen(
                        hasExifTool = hasExifTool,
                        hasFFmpeg = hasFFmpeg,
                        onVerifyDependencies = {
                            hasExifTool = mediaProcessor.checkExifTool()
                            hasFFmpeg = mediaProcessor.checkFFmpeg()
                        },
                        onRunInstaller = {
                            coroutineScope.launch {
                                isInstalling = true
                                // Simulated installer process running on Desktop
                                logs.add("[INFO] Triggering automatic dependency installation...")
                                kotlinx.coroutines.delay(2000)
                                hasExifTool = mediaProcessor.checkExifTool()
                                hasFFmpeg = mediaProcessor.checkFFmpeg()
                                isInstalling = false
                                logs.add("[SUCCESS] System checks completed.")
                            }
                        },
                        isInstalling = isInstalling
                    )
                    Screen.Settings -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
fun NavigationLink(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Color.White.copy(alpha = 0.1f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (active) ElectricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
