package com.najdev.snapvault.viewmodel

import androidx.compose.runtime.*
import com.najdev.snapvault.*
import com.najdev.snapvault.downloader.Deduplicator
import com.najdev.snapvault.downloader.DownloadEngine
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.model.FileMeta
import com.najdev.snapvault.parser.*
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.TimeSource

class DashboardViewModel(
    private val zipPipelineRunner: ZipPipelineRunner,
    private val mediaProcessor: MediaProcessor,
    private val fileSystem: FileSystem,
    private val pickers: PlatformPickers,
) {
    // ── Input selection state ────────────────────────────────────────────────
    var htmlFile by mutableStateOf<String?>(null)
        private set
    var downloadFolder by mutableStateOf<String?>(null)
        private set
    var importMode by mutableStateOf(ImportMode.Zip)
        private set
    var zipSourceMode by mutableStateOf(ZipSourceMode.SingleFile)
        private set
    var zipFolder by mutableStateOf<String?>(null)
        private set
    var singleZipFile by mutableStateOf<String?>(null)
        private set

    // ── Pipeline run state ───────────────────────────────────────────────────
    var isRunning by mutableStateOf(false)
        private set
    val logs = mutableStateListOf<String>()
    var progress by mutableStateOf(0f)
        private set
    var progressText by mutableStateOf("")
        private set
    var speedText by mutableStateOf("SPEED: --")
        private set
    var etaText by mutableStateOf("ETA: --")
        private set
    var currentStep by mutableStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var syncJob: Job? = null

    // ── Picker actions ───────────────────────────────────────────────────────
    fun pickHtmlFile() = pickers.pickHtmlFile { it?.let { path -> htmlFile = path } }
    fun pickOutputFolder() = pickers.pickFolder { it?.let { path -> downloadFolder = path } }
    fun pickZipFolder() = pickers.pickFolder { it?.let { path -> zipFolder = path; singleZipFile = null } }
    fun pickSingleZip() = pickers.pickZipFile { it?.let { path -> singleZipFile = path; zipFolder = null } }

    fun changeImportMode(mode: ImportMode) { importMode = mode }

    fun changeZipSourceMode(mode: ZipSourceMode) {
        zipSourceMode = mode
        zipFolder = null
        singleZipFile = null
    }

    // ── Pipeline control ─────────────────────────────────────────────────────
    fun startSync(
        runDownload: Boolean,
        runMetadata: Boolean,
        runCombine: Boolean,
        runDedupe: Boolean,
        dryRun: Boolean,
        workerCount: Int,
    ) {
        isRunning = true
        logs.clear()
        progress = 0f
        currentStep = 1
        speedText = "SPEED: --"
        etaText = "ETA: --"

        syncJob = scope.launch {
            try {
                val outDir = downloadFolder ?: run {
                    logs.add("[ERROR] No output folder selected.")
                    isRunning = false
                    return@launch
                }
                if (importMode == ImportMode.Zip) {
                    runZipPipeline(outDir, runMetadata, runCombine, runDedupe, dryRun, workerCount)
                } else {
                    runLegacyPipeline(outDir, runDownload, runMetadata, runDedupe, dryRun, workerCount)
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
    }

    fun stopSync() {
        syncJob?.cancel()
        logs.add("[WARN] Sync cancelled by user.")
        isRunning = false
        currentStep = 0
        progressText = "Cancelled"
    }

    fun dispose() = scope.cancel()

    private fun formatEta(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }

    // ── ZIP pipeline ─────────────────────────────────────────────────────────
    private suspend fun runZipPipeline(
        outDir: String,
        runMetadata: Boolean,
        runCombine: Boolean,
        runDedupe: Boolean,
        dryRun: Boolean,
        workerCount: Int,
    ) {
        logs.add("[INFO] Scanning for ZIP file(s)…")
        val zipFiles: List<String> = when (zipSourceMode) {
            ZipSourceMode.SingleFile -> {
                val f = singleZipFile ?: run {
                    logs.add("[ERROR] No ZIP file selected.")
                    isRunning = false
                    return
                }
                listOf(f)
            }
            ZipSourceMode.Folder -> {
                val dir = zipFolder ?: run {
                    logs.add("[ERROR] No ZIP folder selected.")
                    isRunning = false
                    return
                }
                zipPipelineRunner.listZipFiles(dir)
            }
        }

        if (zipFiles.isEmpty()) {
            logs.add("[WARN] No .zip files found in selected folder.")
            isRunning = false
            return
        }
        logs.add("[INFO] Found ${zipFiles.size} zip file(s).")

        val numberedSuffixRegex = Regex("""-\d+\.zip$""")
        val mainZip = zipFiles.firstOrNull { path ->
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            !numberedSuffixRegex.containsMatchIn(name)
        }

        var jsonEntries: List<JsonMemoryEntry> = emptyList()
        var histEntries: List<HistMemoryEntry> = emptyList()
        if (mainZip != null) {
            val mainZipName = mainZip.substringAfterLast('/').substringAfterLast('\\')
            logs.add("[INFO] Reading metadata from $mainZipName…")

            val histContent = readZipEntry(mainZip, "html/memories_history.html")
            if (histContent != null) {
                histEntries = runCatching { ZipHistParser.parse(histContent) }.getOrElse { e ->
                    logs.add("[WARN] Could not parse memories_history.html: ${e.message}")
                    emptyList()
                }
                logs.add("[INFO] Parsed ${histEntries.size} entries from memories_history.html (primary metadata source).")
            }

            val jsonContent = readZipEntry(mainZip, "json/memories_history.json")
            if (jsonContent != null) {
                jsonEntries = runCatching { ZipJsonParser.parse(jsonContent) }.getOrElse { e ->
                    logs.add("[WARN] Could not parse memories_history.json: ${e.message}")
                    emptyList()
                }
                logs.add("[INFO] Parsed ${jsonEntries.size} entries from memories_history.json (fallback metadata source).")
            }

            if (histEntries.isEmpty() && jsonEntries.isEmpty()) {
                logs.add("[WARN] No metadata sources found in main zip — GPS/datetime enrichment unavailable.")
            }
        }

        val itemsByZip = mutableMapOf<String, List<HtmlMemoryEntry>>()
        val allHtmlEntries = mutableListOf<HtmlMemoryEntry>()
        for (zipPath in zipFiles) {
            val htmlContent = readZipEntry(zipPath, "memories/memories.html") ?: continue
            val entries = ZipImportParser.parseMemoriesHtml(htmlContent)
            itemsByZip[zipPath] = entries
            allHtmlEntries.addAll(entries)
        }
        logs.add("[INFO] Indexed ${allHtmlEntries.size} media items across ${itemsByZip.size} zip(s).")

        if (AppBuildConfig.IS_DEBUG) {
            logs.add("[DEBUG] Limiting to 50 items (debug mode)")
            var remaining = 50
            val trimmed = mutableMapOf<String, List<HtmlMemoryEntry>>()
            for ((k, v) in itemsByZip) {
                if (remaining <= 0) break
                trimmed[k] = v.take(remaining)
                remaining -= v.size
            }
            itemsByZip.clear()
            itemsByZip.putAll(trimmed)
        }

        val correlationMap = if (histEntries.isNotEmpty() || jsonEntries.isNotEmpty()) {
            MetadataCorrelator.correlate(histEntries, jsonEntries, allHtmlEntries)
        } else emptyMap()
        logs.add("[INFO] Metadata correlated for ${correlationMap.size} items.")

        val vaultIndexPath = "$outDir/vault_index.json".toPath()
        val downloadedMeta: MutableMap<String, FileMeta> = runCatching {
            Json.decodeFromString<Map<String, FileMeta>>(fileSystem.read(vaultIndexPath) { readUtf8() })
        }.getOrDefault(emptyMap()).toMutableMap()

        logs.add("[INFO] Extracting media files…")
        var extractedCount = 0
        var skippedCount = 0
        val totalItems = itemsByZip.values.sumOf { list ->
            list.sumOf { entry -> if (entry.hasOverlay && entry.overlayFileName != null) 2 else 1 }
        }
        progressText = "Extracting files…"
        val extractEta = EtaEstimator()

        zipPipelineRunner.extractAll(itemsByZip, outDir, workerCount) { result ->
            if (result.error != null) {
                logs.add("[ERROR] ${result.fileName}: ${result.error}")
            } else if (result.skipped) {
                skippedCount++
            } else {
                extractedCount++
            }
            val done = extractedCount + skippedCount
            extractEta.record(done)
            progress = done.toFloat() / totalItems.coerceAtLeast(1)
            progressText = "Extracting: $done / $totalItems"
            val rate = extractEta.ratePerSec()
            if (rate != null) {
                speedText = "${rate.toInt().coerceAtLeast(1)} files/s"
                etaText = when (val etaSec = extractEta.etaSeconds(totalItems, done)) {
                    null -> "ETA: --"
                    0L -> "ETA: done"
                    else -> "ETA: ${formatEta(etaSec)}"
                }
            }
        }
        speedText = "SPEED: --"
        etaText = "ETA: --"
        logs.add("[INFO] Extracted: $extractedCount, Skipped: $skippedCount")
        currentStep = 2

        if (runMetadata) {
            logs.add("[INFO] Writing metadata to extracted files…")
            val metaEntries = itemsByZip.values.flatten()
            val metaTotal = metaEntries.size
            var metaCount = 0
            var metaDone = 0
            val metaEta = EtaEstimator()
            for (entry in metaEntries) {
                currentCoroutineContext().ensureActive()
                val outputPath = "$outDir/${entry.fileName}"
                val corr = correlationMap[entry.uuid]
                val dateTime = corr?.fullDateTime ?: "${entry.date} 00:00:00 UTC"
                val dateOk = mediaProcessor.writeDateMetadata(outputPath, dateTime)
                var gpsOk = false
                if (corr?.latitude != null && corr.longitude != null) {
                    gpsOk = mediaProcessor.writeGpsMetadata(outputPath, corr.latitude, corr.longitude, dateTime)
                }
                downloadedMeta[entry.fileName] = FileMeta(hasGps = gpsOk, hasOverlay = entry.hasOverlay)
                if (dateOk) metaCount++
                metaDone++
                metaEta.record(metaDone)
                progress = metaDone.toFloat() / metaTotal.coerceAtLeast(1)
                progressText = "Metadata: $metaDone / $metaTotal"
                val rate = metaEta.ratePerSec()
                if (rate != null) {
                    speedText = "${rate.toInt().coerceAtLeast(1)} files/s"
                    etaText = when (val etaSec = metaEta.etaSeconds(metaTotal, metaDone)) {
                        null -> "ETA: --"
                        0L -> "ETA: done"
                        else -> "ETA: ${formatEta(etaSec)}"
                    }
                }
            }
            speedText = "SPEED: --"
            etaText = "ETA: --"
            logs.add("[INFO] Metadata written to $metaCount files.")
        }

        if (runCombine) {
            logs.add("[INFO] Combining overlays…")
            var combinedCount = 0
            zipPipelineRunner.combineAll(outDir, deleteOriginals = true, workerCount = workerCount) { result ->
                when {
                    result.status == "combined" -> { combinedCount++; logs.add("[COMBINED] ${result.uuid}") }
                    result.status.startsWith("error") -> logs.add("[ERROR] ${result.uuid}: ${result.status}")
                }
            }
            logs.add("[INFO] Combined $combinedCount overlay pairs.")
        }

        if (runDedupe) runDeduplication(outDir, dryRun)

        runCatching {
            fileSystem.write(vaultIndexPath) {
                writeUtf8(Json.encodeToString<Map<String, FileMeta>>(downloadedMeta))
            }
            logs.add("[INFO] Vault index saved (${downloadedMeta.size} entries).")
        }.onFailure { e -> logs.add("[WARN] Could not write vault index: ${e.message}") }
    }

    // ── Legacy pipeline ──────────────────────────────────────────────────────
    private suspend fun runLegacyPipeline(
        outDir: String,
        runDownload: Boolean,
        runMetadata: Boolean,
        runDedupe: Boolean,
        dryRun: Boolean,
        workerCount: Int,
    ) {
        progressText = "Reading memories…"
        logs.add("[INFO] Starting pipeline sequence…")

        val htmlPath = htmlFile ?: return
        val fileContent = fileSystem.read(htmlPath.toPath()) { readUtf8() }
        val isJson = htmlPath.endsWith(".json", ignoreCase = true)
        logs.add("[INFO] Parsing memories history (${if (isJson) "JSON" else "HTML"})…")

        val parsed = if (isJson) HistoryParser.parseJson(fileContent) else HistoryParser.parse(fileContent)
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
            return
        }

        val vaultIndexPath = "$outDir/vault_index.json".toPath()
        val downloadedMeta: MutableMap<String, FileMeta> = runCatching {
            Json.decodeFromString<Map<String, FileMeta>>(fileSystem.read(vaultIndexPath) { readUtf8() })
        }.getOrDefault(emptyMap()).toMutableMap()

        if (runDownload) {
            logs.add("[INFO] Downloading files in parallel…")
            val httpClient = HttpClient()
            val downloader = DownloadEngine(httpClient, fileSystem)
            var downloadedCount = 0
            val totalCount = items.size
            val dlEta = EtaEstimator()

            val downloadCollector = downloader.progressFlow.onEach { (item, status) ->
                downloadedCount++
                dlEta.record(downloadedCount)
                progress = downloadedCount.toFloat() / totalCount
                progressText = "Downloading: $downloadedCount of $totalCount files…"
                val rate = dlEta.ratePerSec()
                if (rate != null) {
                    speedText = "${rate.toInt().coerceAtLeast(1)} files/s"
                    etaText = when (val etaSec = dlEta.etaSeconds(totalCount, downloadedCount)) {
                        null -> "ETA: --"
                        0L -> "ETA: done"
                        else -> "ETA: ${formatEta(etaSec)}"
                    }
                }

                val name = item.downloadedPath?.let { path ->
                    val lastSlash = path.lastIndexOfAny(charArrayOf('/', '\\'))
                    if (lastSlash != -1) path.substring(lastSlash + 1) else path
                } ?: downloader.buildFilename(item, null)

                when (status) {
                    "downloaded" -> {
                        logs.add("[DOWNLOADED] $name")
                        var hasGps = false
                        if (runMetadata && item.latitude != null && item.longitude != null && item.downloadedPath != null) {
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
        if (runDedupe) runDeduplication(outDir, dryRun)

        runCatching {
            fileSystem.write(vaultIndexPath) {
                writeUtf8(Json.encodeToString<Map<String, FileMeta>>(downloadedMeta))
            }
            logs.add("[INFO] Vault index saved (${downloadedMeta.size} entries).")
        }.onFailure { e -> logs.add("[WARN] Could not write vault index: ${e.message}") }
    }

    private suspend fun runDeduplication(outDir: String, dryRun: Boolean) {
        logs.add("[INFO] Scanning for duplicate files…")
        val deduplicator = Deduplicator(fileSystem)
        val results = deduplicator.deduplicateFolder(outDir.toPath(), dryRun)
        if (results.isEmpty()) {
            logs.add("[SUCCESS] No duplicate files found.")
        } else {
            results.forEach { res ->
                logs.add("[DELETED DUPES] Kept ${res.keptFile}, deleted: ${res.deletedFiles.joinToString()}")
            }
        }
    }
}

private class EtaEstimator(private val windowMs: Long = 30_000L) {
    private data class Sample(val elapsedMs: Long, val done: Int)
    private val samples = ArrayDeque<Sample>()
    private val start = TimeSource.Monotonic.markNow()

    fun record(done: Int) {
        val now = start.elapsedNow().inWholeMilliseconds
        samples.addLast(Sample(now, done))
        val cutoff = now - windowMs
        while (samples.size > 2 && samples.first().elapsedMs < cutoff) samples.removeFirst()
    }

    fun ratePerSec(): Double? {
        if (samples.size < 2) return null
        val oldest = samples.first()
        val newest = samples.last()
        val windowDurationMs = (newest.elapsedMs - oldest.elapsedMs).coerceAtLeast(1)
        val delta = newest.done - oldest.done
        if (delta <= 0) return null
        return delta.toDouble() / windowDurationMs * 1000.0
    }

    fun etaSeconds(total: Int, done: Int): Long? {
        val rate = ratePerSec() ?: return null
        val remaining = (total - done).coerceAtLeast(0)
        if (remaining == 0) return 0L
        return (remaining / rate).toLong()
    }
}
