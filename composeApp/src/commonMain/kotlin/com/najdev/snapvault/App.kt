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
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.model.FileMeta
import com.najdev.snapvault.parser.HistoryParser
import com.najdev.snapvault.parser.MetadataCorrelator
import com.najdev.snapvault.parser.ZipImportParser
import com.najdev.snapvault.parser.ZipJsonParser
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.najdev.snapvault.ui.DashboardScreen
import com.najdev.snapvault.ui.LibraryScreen
import com.najdev.snapvault.ui.SettingsScreen
import com.najdev.snapvault.ui.theme.ElectricPurple
import com.najdev.snapvault.ui.theme.SnapVaultTheme
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
enum class ImportMode { Legacy, Zip }
enum class ZipSourceMode { SingleFile, Folder }

@Composable
fun App(
    pickers: PlatformPickers,
    mediaProcessor: MediaProcessor,
    zipPipelineRunner: ZipPipelineRunner,
    fileSystem: FileSystem,
    showWindowControls: Boolean = false,
    onCloseWindow: () -> Unit = {},
    onMinimizeWindow: () -> Unit = {},
    onMaximizeWindow: () -> Unit = {},
) {
    var currentScreen by remember { mutableStateOf(Screen.Dashboard) }

    var htmlFile by remember { mutableStateOf<String?>(null) }
    var downloadFolder by remember { mutableStateOf<String?>(null) }
    var importMode by remember { mutableStateOf(ImportMode.Zip) }
    var zipSourceMode by remember { mutableStateOf(ZipSourceMode.SingleFile) }
    var zipFolder by remember { mutableStateOf<String?>(null) }
    var singleZipFile by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }
    var progress by remember { mutableStateOf(0f) }
    var progressText by remember { mutableStateOf("") }
    var speedText by remember { mutableStateOf("SPEED: --") }
    var etaText by remember { mutableStateOf("ETA: --") }
    var currentStep by remember { mutableStateOf(0) }

    var isDarkMode by remember { mutableStateOf(loadThemePreference()) }
    var workers by remember { mutableStateOf(5) }

    var hasExifTool by remember { mutableStateOf(false) }
    var hasFFmpeg by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasExifTool = mediaProcessor.checkExifTool()
        hasFFmpeg = mediaProcessor.checkFFmpeg()
    }

    val coroutineScope = rememberCoroutineScope()
    var syncJob by remember { mutableStateOf<Job?>(null) }

    SnapVaultTheme(darkMode = isDarkMode) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // ── Top Bar ─────────────────────────────────────────────────────────
            DraggableArea {
            Surface(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                                text = AppBuildConfig.VERSION,
                                fontSize = 10.sp,
                                color = ElectricPurple,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                        if (AppBuildConfig.IS_DEBUG) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFFBBF24).copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    "DEBUG",
                                    fontSize = 9.sp,
                                    color = Color(0xFFFBBF24),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
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
                        if (showWindowControls) {
                            Box(
                                Modifier
                                    .width(1.dp)
                                    .height(16.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                WindowControlButton("−", onMinimizeWindow)
                                WindowControlButton("▢", onMaximizeWindow)
                                WindowControlButton("×", onCloseWindow)
                            }
                        }
                    }
                }
            }
            }

            Row(modifier = Modifier.fillMaxSize()) {
                // ── Sidebar ──────────────────────────────────────────────────────
                Surface(
                    modifier = Modifier.width(220.dp).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp)
                    ) {
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
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
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
                            importMode = importMode,
                            zipSourceMode = zipSourceMode,
                            onZipSourceModeChange = { mode ->
                                zipSourceMode = mode
                                zipFolder = null
                                singleZipFile = null
                            },
                            zipFolder = zipFolder,
                            singleZipFile = singleZipFile,
                            onSelectZipFolder = {
                                pickers.pickFolder { result -> result?.let { zipFolder = it; singleZipFile = null } }
                            },
                            onSelectSingleZip = {
                                pickers.pickZipFile { result -> result?.let { singleZipFile = it; zipFolder = null } }
                            },
                            onImportModeChange = { importMode = it },
                            onStartSync = { download, metadata, runCombine, dedupe, dry, workerCount ->
                                isRunning = true
                                logs.clear()
                                progress = 0f
                                currentStep = 1

                                syncJob = coroutineScope.launch(Dispatchers.Default) {
                                    try {
                                        val outDir = downloadFolder ?: run {
                                            logs.add("[ERROR] No output folder selected.")
                                            isRunning = false
                                            return@launch
                                        }

                                        if (importMode == ImportMode.Zip) {
                                            // ── ZIP IMPORT PIPELINE ──────────────────────────────────────────
                                            logs.add("[INFO] Scanning for ZIP file(s)…")
                                            val zipFiles: List<String> = when (zipSourceMode) {
                                                ZipSourceMode.SingleFile -> {
                                                    val f = singleZipFile ?: run {
                                                        logs.add("[ERROR] No ZIP file selected.")
                                                        isRunning = false
                                                        return@launch
                                                    }
                                                    listOf(f)
                                                }
                                                ZipSourceMode.Folder -> {
                                                    val dir = zipFolder ?: run {
                                                        logs.add("[ERROR] No ZIP folder selected.")
                                                        isRunning = false
                                                        return@launch
                                                    }
                                                    zipPipelineRunner.listZipFiles(dir)
                                                }
                                            }

                                            if (zipFiles.isEmpty()) {
                                                logs.add("[WARN] No .zip files found in selected folder.")
                                                isRunning = false
                                                return@launch
                                            }
                                            logs.add("[INFO] Found ${zipFiles.size} zip file(s).")

                                            // Find main zip (no numeric suffix like -N)
                                            val numberedSuffixRegex = Regex("""-\d+\.zip$""")
                                            val mainZip = zipFiles.firstOrNull { path ->
                                                val name = path.substringAfterLast('/').substringAfterLast('\\')
                                                !numberedSuffixRegex.containsMatchIn(name)
                                            }

                                            // Parse JSON metadata from main zip
                                            var jsonEntries: List<com.najdev.snapvault.parser.JsonMemoryEntry> = emptyList()
                                            if (mainZip != null) {
                                                val mainZipName = mainZip.substringAfterLast('/').substringAfterLast('\\')
                                                logs.add("[INFO] Reading metadata from $mainZipName…")
                                                val jsonContent = readZipEntry(mainZip, "json/memories_history.json")
                                                if (jsonContent != null) {
                                                    jsonEntries = runCatching {
                                                        ZipJsonParser.parse(jsonContent)
                                                    }.getOrElse { e ->
                                                        logs.add("[WARN] Could not parse memories_history.json: ${e.message}")
                                                        emptyList()
                                                    }
                                                    logs.add("[INFO] Parsed ${jsonEntries.size} metadata entries from JSON.")
                                                } else {
                                                    logs.add("[WARN] memories_history.json not found in main zip — GPS/datetime enrichment unavailable.")
                                                }
                                            }

                                            // Parse memories.html from all zips, accumulate in zip order (oldest-first)
                                            val itemsByZip = mutableMapOf<String, List<com.najdev.snapvault.parser.HtmlMemoryEntry>>()
                                            val allHtmlEntries = mutableListOf<com.najdev.snapvault.parser.HtmlMemoryEntry>()
                                            for (zipPath in zipFiles) {
                                                val htmlContent = readZipEntry(zipPath, "memories/memories.html") ?: continue
                                                val entries = ZipImportParser.parseMemoriesHtml(htmlContent)
                                                itemsByZip[zipPath] = entries
                                                allHtmlEntries.addAll(entries)
                                            }
                                            logs.add("[INFO] Indexed ${allHtmlEntries.size} media items across ${itemsByZip.size} zip(s).")

                                            if (AppBuildConfig.IS_DEBUG) {
                                                logs.add("[DEBUG] Limiting to 50 items (debug mode)")
                                                // Trim itemsByZip to only first 50 total items
                                                var remaining = 50
                                                val trimmed = mutableMapOf<String, List<com.najdev.snapvault.parser.HtmlMemoryEntry>>()
                                                for ((k, v) in itemsByZip) {
                                                    if (remaining <= 0) break
                                                    trimmed[k] = v.take(remaining)
                                                    remaining -= v.size
                                                }
                                                itemsByZip.clear()
                                                itemsByZip.putAll(trimmed)
                                            }

                                            // Correlate JSON metadata → UUID map
                                            val correlationMap = if (jsonEntries.isNotEmpty()) {
                                                MetadataCorrelator.correlate(jsonEntries, allHtmlEntries)
                                            } else emptyMap()
                                            logs.add("[INFO] Metadata correlated for ${correlationMap.size} items.")

                                            // Load existing vault index
                                            val vaultIndexPath = "$outDir/vault_index.json".toPath()
                                            val downloadedMeta: MutableMap<String, FileMeta> = runCatching {
                                                Json.decodeFromString<Map<String, FileMeta>>(
                                                    fileSystem.read(vaultIndexPath) { readUtf8() }
                                                )
                                            }.getOrDefault(emptyMap()).toMutableMap()

                                            // Extract files
                                            logs.add("[INFO] Extracting media files…")
                                            var extractedCount = 0
                                            var skippedCount = 0
                                            val totalItems = itemsByZip.values.sumOf { it.size }
                                            progressText = "Extracting files…"

                                            zipPipelineRunner.extractAll(itemsByZip, outDir, workerCount) { result ->
                                                if (result.error != null) {
                                                    logs.add("[ERROR] ${result.fileName}: ${result.error}")
                                                } else if (result.skipped) {
                                                    skippedCount++
                                                } else {
                                                    extractedCount++
                                                    val done = extractedCount + skippedCount
                                                    progress = done.toFloat() / totalItems.coerceAtLeast(1)
                                                    progressText = "Extracting: $done / $totalItems"
                                                }
                                            }
                                            logs.add("[INFO] Extracted: $extractedCount, Skipped: $skippedCount")
                                            currentStep = 2

                                            // Write date/GPS metadata
                                            if (metadata) {
                                                logs.add("[INFO] Writing metadata to extracted files…")
                                                var metaCount = 0
                                                val mainEntries = itemsByZip.values.flatten()
                                                for (entry in mainEntries) {
                                                    val outputPath = "$outDir/${entry.fileName}"
                                                    val corr = correlationMap[entry.uuid]
                                                    val dateTime = corr?.fullDateTime ?: "${entry.date} 00:00:00 UTC"

                                                    val dateOk = mediaProcessor.writeDateMetadata(outputPath, dateTime)
                                                    var gpsOk = false
                                                    if (corr?.latitude != null && corr.longitude != null) {
                                                        gpsOk = mediaProcessor.writeGpsMetadata(outputPath, corr.latitude, corr.longitude, dateTime)
                                                    }

                                                    val hasOverlay = entry.hasOverlay
                                                    downloadedMeta[entry.fileName] = FileMeta(
                                                        hasGps = gpsOk,
                                                        hasOverlay = hasOverlay
                                                    )
                                                    if (dateOk) metaCount++
                                                }
                                                logs.add("[INFO] Metadata written to $metaCount files.")
                                            }

                                            // Combine overlays
                                            if (runCombine) {
                                                logs.add("[INFO] Combining overlays…")
                                                var combinedCount = 0
                                                zipPipelineRunner.combineAll(outDir, deleteOriginals = true, workerCount = workerCount) { result ->
                                                    when {
                                                        result.status == "combined" -> {
                                                            combinedCount++
                                                            logs.add("[COMBINED] ${result.uuid}")
                                                        }
                                                        result.status.startsWith("error") -> logs.add("[ERROR] ${result.uuid}: ${result.status}")
                                                    }
                                                }
                                                logs.add("[INFO] Combined $combinedCount overlay pairs.")
                                            }

                                            // Deduplicate
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

                                            // Write vault index
                                            runCatching {
                                                fileSystem.write(vaultIndexPath) {
                                                    writeUtf8(Json.encodeToString<Map<String, FileMeta>>(downloadedMeta))
                                                }
                                                logs.add("[INFO] Vault index saved (${downloadedMeta.size} entries).")
                                            }.onFailure { e -> logs.add("[WARN] Could not write vault index: ${e.message}") }

                                        } else {
                                            // ── LEGACY PIPELINE ──────────────────────────────────────────────
                                            progressText = "Reading memories…"
                                            logs.add("[INFO] Starting pipeline sequence…")

                                            val htmlPath = htmlFile ?: return@launch

                                            val fileContent = fileSystem.read(htmlPath.toPath()) { readUtf8() }
                                            val isJson = htmlPath.endsWith(".json", ignoreCase = true)
                                            logs.add("[INFO] Parsing memories history (${if (isJson) "JSON" else "HTML"})…")
                                            val parsed = if (isJson) HistoryParser.parseJson(fileContent)
                                                         else HistoryParser.parse(fileContent)
                                            logs.add("[INFO] File size: ${fileContent.length / 1024}KB — parsed ${parsed.size} items")
                                            if (parsed.isEmpty() && isJson)
                                                logs.add("[DEBUG] JSON parsed but 0 items — verify the file contains a 'Saved Media' array")
                                            if (parsed.isEmpty() && !isJson) logs.add("[DEBUG] ${HistoryParser.diagnose(fileContent)}")
                                            if (AppBuildConfig.IS_DEBUG) logs.add("[DEBUG] Limiting to 5 items (debug mode)")
                                            val items = if (AppBuildConfig.IS_DEBUG) parsed.take(5) else parsed

                                            if (items.isEmpty()) {
                                                logs.add("[WARN] No items found. Use memories_history.json from your Snapchat export (mydata.snapchat.com).")
                                                isRunning = false
                                                currentStep = 3
                                                return@launch
                                            }

                                            val vaultIndexPath = "$outDir/vault_index.json".toPath()
                                            val downloadedMeta: MutableMap<String, FileMeta> = runCatching {
                                                Json.decodeFromString<Map<String, FileMeta>>(
                                                    fileSystem.read(vaultIndexPath) { readUtf8() }
                                                )
                                            }.getOrDefault(emptyMap()).toMutableMap()

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
                                                            var hasGps = false
                                                            if (metadata && hasExifTool && item.latitude != null && item.longitude != null && item.downloadedPath != null) {
                                                                val exifOk = mediaProcessor.writeGpsMetadata(item.downloadedPath, item.latitude, item.longitude, item.dateStr)
                                                                if (exifOk) { logs.add("   -> [METADATA] EXIF GPS tag written."); hasGps = true }
                                                            }
                                                            downloadedMeta[name] = FileMeta(hasGps = hasGps, hasOverlay = false)
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

                                            runCatching {
                                                fileSystem.write(vaultIndexPath) {
                                                    writeUtf8(Json.encodeToString<Map<String, FileMeta>>(downloadedMeta))
                                                }
                                                logs.add("[INFO] Vault index saved (${downloadedMeta.size} entries).")
                                            }.onFailure { e ->
                                                logs.add("[WARN] Could not write vault index: ${e.message}")
                                            }
                                        }

                                        progress = 1.0f
                                        progressText = "Pipeline Complete"
                                        currentStep = 3
                                        logs.add("[SUCCESS] Sync complete!")
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
                            currentStep = currentStep,
                            workers = workers
                        )
                        Screen.Library -> LibraryScreen(
                            downloadFolder = downloadFolder,
                            onOpenFolder = {
                                pickers.pickFolder { result -> result?.let { downloadFolder = it } }
                            }
                        )
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
                            isInstalling = isInstalling,
                            workers = workers,
                            onWorkersChange = { workers = it },
                            isDarkMode = isDarkMode,
                            onToggleDarkMode = { isDarkMode = it; saveThemePreference(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowControlButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontWeight = FontWeight.Normal
        )
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
