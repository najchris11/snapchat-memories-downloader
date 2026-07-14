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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.TimeSource

private class PipelineAbortException(message: String) : Exception(message)

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
    var zipSourceMode by mutableStateOf(ZipSourceMode.Folder)
        private set
    var zipFolder by mutableStateOf<String?>(null)
        private set
    var selectedZipFiles by mutableStateOf<List<String>>(emptyList())
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
    private val workerCount = computeWorkerCount()

    // ── Picker actions ───────────────────────────────────────────────────────
    fun pickHtmlFile() = pickers.pickHtmlFile { it?.let { path -> htmlFile = path } }
    fun pickOutputFolder() = pickers.pickFolder { it?.let { path -> downloadFolder = path } }
    fun pickZipFolder() = pickers.pickFolder { it?.let { path -> zipFolder = path; selectedZipFiles = emptyList() } }
    fun pickMultipleZips() = pickers.pickMultipleZips { paths -> if (paths.isNotEmpty()) { selectedZipFiles = paths; zipFolder = null } }

    fun changeImportMode(mode: ImportMode) { importMode = mode }

    fun changeZipSourceMode(mode: ZipSourceMode) {
        zipSourceMode = mode
        zipFolder = null
        selectedZipFiles = emptyList()
    }

    // ── Pipeline control ─────────────────────────────────────────────────────
    fun startSync(
        runDownload: Boolean,
        runMetadata: Boolean,
        runCombine: Boolean,
        runDedupe: Boolean,
        dryRun: Boolean,
    ) {
        isRunning = true
        logs.clear()
        progress = 0f
        currentStep = 1
        speedText = "SPEED: --"
        etaText = "ETA: --"

        syncJob = scope.launch {
            try {
                val outDir = downloadFolder ?: throw PipelineAbortException("No output folder selected.")
                if (importMode == ImportMode.Zip) {
                    runZipPipeline(outDir, runMetadata, runCombine, runDedupe, dryRun, workerCount)
                } else {
                    runLegacyPipeline(outDir, runDownload, runMetadata, runDedupe, dryRun, workerCount)
                }
                progress = 1.0f
                progressText = "Pipeline Complete"
                currentStep = 3
                logs.add("[SUCCESS] Sync complete!")
            } catch (e: CancellationException) {
                throw e
            } catch (e: PipelineAbortException) {
                logs.add("[ERROR] ${e.message}")
                progressText = "Failed"
                currentStep = 0
            } catch (e: Exception) {
                logs.add("[ERROR] Pipeline failed: ${e.message}")
                progressText = "Failed"
                currentStep = 0
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

    fun resetVaultIndex(): Boolean {
        val folder = downloadFolder ?: return false
        return runCatching {
            val path = "$folder/vault_index.json".toPath()
            if (fileSystem.exists(path)) fileSystem.delete(path)
            true
        }.getOrDefault(false)
    }

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
            ZipSourceMode.Folder -> {
                zipPipelineRunner.listZipFiles(
                    zipFolder ?: throw PipelineAbortException("No ZIP folder selected.")
                )
            }
            ZipSourceMode.MultipleFiles -> {
                selectedZipFiles.ifEmpty { throw PipelineAbortException("No ZIP files selected.") }
            }
        }

        if (zipFiles.isEmpty()) throw PipelineAbortException("No .zip files found in selected folder.")
        logs.add("[INFO] Found ${zipFiles.size} zip file(s).")

        val itemsByZip = mutableMapOf<String, List<HtmlMemoryEntry>>()
        var totalMemoryCount = 0
        for (zipPath in zipFiles) {
            val zipName = zipPath.substringAfterLast('/').substringAfterLast('\\')
            var unmatchedCount = 0
            val unmatchedSamples = mutableListOf<String>()
            val entries = ZipImportParser.parseMemoriesFromZip(zipPath) { filePath ->
                unmatchedCount++
                if (unmatchedSamples.size < 5) unmatchedSamples.add(filePath.substringAfterLast('/'))
            }
            itemsByZip[zipPath] = entries
            totalMemoryCount += entries.size
            val parsedFileCount = entries.size + entries.count { it.overlayFileName != null }
            val dateRange = if (entries.isNotEmpty()) {
                val sorted = entries.map { it.date }.sorted()
                "${sorted.first()} – ${sorted.last()}"
            } else "no entries"
            logs.add("[INFO] $zipName: ${entries.size} memories, $parsedFileCount files parsed ($dateRange)")
            if (unmatchedCount > 0) {
                logs.add("[WARN] $zipName: $unmatchedCount file(s) in memories/ skipped — unexpected filename format. Examples: ${unmatchedSamples.joinToString(", ")}")
            }
        }
        logs.add("[INFO] Indexed $totalMemoryCount memories across ${itemsByZip.size} zip(s).")

        if (AppBuildConfig.IS_DEBUG) {
            logs.add("[DEBUG] Limiting to 2500 items (debug mode)")
            var remaining = 2500
            val trimmed = mutableMapOf<String, List<HtmlMemoryEntry>>()
            for ((k, v) in itemsByZip) {
                if (remaining <= 0) break
                trimmed[k] = v.take(remaining)
                remaining -= v.size
            }
            itemsByZip.clear()
            itemsByZip.putAll(trimmed)
        }

        val vaultIndexPath = "$outDir/vault_index.json".toPath()
        val downloadedMeta: MutableMap<String, FileMeta> = runCatching {
            Json.decodeFromString<Map<String, FileMeta>>(fileSystem.read(vaultIndexPath) { readUtf8() })
        }.getOrDefault(emptyMap()).toMutableMap()

        logs.add("[INFO] Extracting media files…")
        var extractedCount = 0
        var skippedCount = 0
        var extractErrorCount = 0
        val totalItems = itemsByZip.values.sumOf { list ->
            list.sumOf { entry -> if (entry.hasOverlay && entry.overlayFileName != null) 2 else 1 }
        }
        progressText = "Extracting files…"
        val extractEta = EtaEstimator()

        zipPipelineRunner.extractAll(itemsByZip, outDir, workerCount) { result ->
            if (result.error != null) {
                extractErrorCount++
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
        val extractSummary = buildString {
            append("[INFO] Extracted $extractedCount new, $skippedCount already existed")
            if (extractErrorCount > 0) append(", $extractErrorCount errors")
            append(".")
        }
        logs.add(extractSummary)
        currentStep = 2

        var pipelineCombinedCount = 0
        var pipelineCombineSkipped = 0
        var pipelineCombineErrors = 0

        if (runMetadata) {
            val metaEntries = itemsByZip.values.flatten()
            val metaTotal = metaEntries.size
            //META date source: groups by HtmlMemoryEntry.date (YYYY-MM-DD from filename), NOT from hist/json correlation
            //META GPS is NOT written here — MetadataCorrelator exists but is not called; fullDateTime (time-of-day) also not used
            //META TODO: swap writeDateMetadataBatch for a GPS-aware call once a reliable UUID↔history matching algorithm is in place
            val byDate = metaEntries.groupBy { it.date }
            logs.add("[INFO] Writing date metadata — ${metaTotal} files across ${byDate.size} date group(s)…")

            var metaCount = 0
            var metaDone = 0
            var metaFailCount = 0
            val metaEta = EtaEstimator()

            data class BatchResult(val tagged: Int, val entries: List<HtmlMemoryEntry>)

            val metaChannel = Channel<BatchResult>(Channel.UNLIMITED)
            val metaSemaphore = Semaphore(workerCount)

            coroutineScope {
                // Single consumer — serializes all counter/UI updates
                launch {
                    for (r in metaChannel) {
                        metaCount += r.tagged
                        metaFailCount += (r.entries.size - r.tagged)
                        r.entries.forEach { entry ->
                            downloadedMeta[entry.fileName] = FileMeta(hasGps = false, hasOverlay = entry.hasOverlay)
                        }
                        metaDone += r.entries.size
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
                }

                // One coroutine per date group — semaphore caps concurrent exiftool processes
                byDate.entries.map { (date, entries) ->
                    async(Dispatchers.IO) {
                        currentCoroutineContext().ensureActive()
                        metaSemaphore.withPermit {
                            val paths = entries.map { "$outDir/${it.fileName}" }
                            //META actual write call: date-only batch write; returns count of files successfully tagged (0 for unsupported extensions)
                            val tagged = mediaProcessor.writeDateMetadataBatch(paths, date) { msg ->
                                logs.add("[WARN] $msg")
                            }
                            metaChannel.send(BatchResult(tagged, entries))
                        }
                    }
                }.awaitAll()

                metaChannel.close()
            }

            speedText = "SPEED: --"
            etaText = "ETA: --"
            logs.add("[INFO] Metadata: $metaCount files tagged${if (metaFailCount > 0) ", $metaFailCount could not be tagged (see [WARN] lines above)" else ""}.")
        }

        if (runCombine) {
            val scannedPairCount = itemsByZip.values.flatten().count { it.hasOverlay && it.overlayFileName != null }
            var combinedCount = 0
            var combineErrorCount = 0
            var combineSkippedCount = 0
            var combineDone = 0
            var combineTotal = scannedPairCount.coerceAtLeast(1)
            val combineEta = EtaEstimator()
            progress = 0f
            progressText = "Combining overlays…"
            zipPipelineRunner.combineAll(
                outDir,
                deleteOriginals = true,
                workerCount = workerCount,
                onMetaError = { msg -> logs.add("[WARN] $msg") },
                onStart = { actual ->
                    combineTotal = actual.coerceAtLeast(1)
                    val encoder = mediaProcessor.activeVideoEncoder()
                    val encoderLabel = if (encoder != null) "hardware ($encoder)" else "software (libx264)"
                    logs.add("[INFO] Found $actual overlay pairs. Combining… [video encoder: $encoderLabel]")
                    progressText = "Combining: 0 / $actual"
                },
                onMetaStart = { total ->
                    logs.add("[INFO] Tagging $total combined file(s) with date metadata…")
                    progressText = "Tagging combined files…"
                    progress = 0f
                }
            ) { result ->
                when {
                    result.status == "combined" -> combinedCount++
                    result.status.startsWith("skipped:") -> combineSkippedCount++
                    result.status.startsWith("error") -> {
                        combineErrorCount++
                        logs.add("[ERROR] Overlay combine ${result.uuid.take(8)}: ${result.status}")
                    }
                }
                combineDone++
                combineEta.record(combineDone)
                progress = combineDone.toFloat() / combineTotal
                progressText = "Combining: $combineDone / $combineTotal"
                val rate = combineEta.ratePerSec()
                if (rate != null) {
                    speedText = "${rate.toInt().coerceAtLeast(1)} pairs/s"
                    etaText = when (val etaSec = combineEta.etaSeconds(combineTotal, combineDone)) {
                        null -> "ETA: --"
                        0L -> "ETA: done"
                        else -> "ETA: ${formatEta(etaSec)}"
                    }
                }
            }
            speedText = "SPEED: --"
            etaText = "ETA: --"
            val combineSummary = buildString {
                append("[INFO] Combined $combinedCount overlay pairs")
                if (combineSkippedCount > 0) append(", $combineSkippedCount skipped (unsupported format)")
                if (combineErrorCount > 0) append(", $combineErrorCount errors")
                append(".")
            }
            logs.add(combineSummary)
            pipelineCombinedCount = combinedCount
            pipelineCombineSkipped = combineSkippedCount
            pipelineCombineErrors = combineErrorCount
        }

        if (runDedupe) runDeduplication(outDir, dryRun)

        runCatching {
            fileSystem.write(vaultIndexPath) {
                writeUtf8(Json.encodeToString<Map<String, FileMeta>>(downloadedMeta))
            }
            logs.add("[INFO] Vault index saved (${downloadedMeta.size} entries).")
        }.onFailure { e -> logs.add("[WARN] Could not write vault index: ${e.message}") }

        val summary = buildString {
            append("[SUCCESS] Done — $totalMemoryCount memories")
            append(", ${extractedCount + skippedCount} files on disk")
            if (runCombine && pipelineCombinedCount > 0) {
                append(", $pipelineCombinedCount overlays combined")
                if (pipelineCombineSkipped > 0) append(" ($pipelineCombineSkipped skipped)")
            }
            if (pipelineCombineErrors > 0) append(", $pipelineCombineErrors combine errors")
            append(".")
        }
        logs.add(summary)
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

        val htmlPath = htmlFile ?: throw PipelineAbortException("No HTML/JSON file selected.")
        val fileContent = fileSystem.read(htmlPath.toPath()) { readUtf8() }
        val isJson = htmlPath.endsWith(".json", ignoreCase = true)
        logs.add("[INFO] Parsing memories history (${if (isJson) "JSON" else "HTML"})…")

        val parsed = if (isJson) HistoryParser.parseJson(fileContent) else HistoryParser.parse(fileContent)
        logs.add("[INFO] File size: ${fileContent.length / 1024}KB — parsed ${parsed.size} items")
        if (parsed.isEmpty() && isJson)
            logs.add("[DEBUG] JSON parsed but 0 items — verify the file contains a 'Saved Media' array")
        if (parsed.isEmpty() && !isJson) logs.add("[DEBUG] ${HistoryParser.diagnose(fileContent)}")
        if (AppBuildConfig.IS_DEBUG) logs.add("[DEBUG] Limiting to 2500 items (debug mode)")

        val items = if (AppBuildConfig.IS_DEBUG) parsed.take(2500) else parsed
        if (items.isEmpty()) throw PipelineAbortException("No items found. Use memories_history.json from your Snapchat export (mydata.snapchat.com).")

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
                        //META legacy pipeline GPS write: only fires if MemoryItem has non-null lat/lon from memories_history.html/json
                        //META dateStr here comes from HistoryParser ("YYYY-MM-DD HH:MM:SS UTC" or similar) and includes time-of-day
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
        progressText = "Deduplicating…"
        val deduplicator = Deduplicator(fileSystem)
        val results = withContext(Dispatchers.IO) {
            deduplicator.deduplicateFolder(outDir.toPath(), dryRun)
        }
        if (results.isEmpty()) {
            logs.add("[INFO] No duplicate files found.")
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
