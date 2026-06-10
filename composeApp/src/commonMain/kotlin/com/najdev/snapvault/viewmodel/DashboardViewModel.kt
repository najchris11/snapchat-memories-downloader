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
            ZipSourceMode.SingleFile -> {
                listOf(singleZipFile ?: throw PipelineAbortException("No ZIP file selected."))
            }
            ZipSourceMode.Folder -> {
                zipPipelineRunner.listZipFiles(
                    zipFolder ?: throw PipelineAbortException("No ZIP folder selected.")
                )
            }
        }

        if (zipFiles.isEmpty()) throw PipelineAbortException("No .zip files found in selected folder.")
        logs.add("[INFO] Found ${zipFiles.size} zip file(s).")

        val numberedSuffixRegex = Regex("""-\d+\.zip$""")
        val mainZip = zipFiles.firstOrNull { path ->
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            !numberedSuffixRegex.containsMatchIn(name)
        }

        var jsonEntries: List<JsonMemoryEntry> = emptyList()
        var histEntries: List<HistMemoryEntry> = emptyList()
        if (mainZip == null) {
            logs.add("[WARN] No unnumbered main zip found — metadata sources unavailable. GPS/datetime enrichment skipped.")
        } else {
            val mainZipName = mainZip.substringAfterLast('/').substringAfterLast('\\')
            logs.add("[INFO] Reading metadata from $mainZipName…")

            val histContent = readZipEntry(mainZip, "html/memories_history.html")
            if (histContent != null) {
                histEntries = runCatching { ZipHistParser.parse(histContent) }.getOrElse { e ->
                    logs.add("[WARN] Could not parse memories_history.html: ${e.message}")
                    emptyList()
                }
                val histWithGps = histEntries.count { it.latitude != null }
                logs.add("[INFO] memories_history.html: ${histEntries.size} entries, $histWithGps with GPS (${if (histEntries.isEmpty()) 0 else histWithGps * 100 / histEntries.size}%).")
            } else {
                logs.add("[WARN] memories_history.html not found in main zip.")
            }

            val jsonContent = readZipEntry(mainZip, "json/memories_history.json")
            if (jsonContent != null) {
                jsonEntries = runCatching { ZipJsonParser.parse(jsonContent) }.getOrElse { e ->
                    logs.add("[WARN] Could not parse memories_history.json: ${e.message}")
                    emptyList()
                }
                logs.add("[INFO] memories_history.json: ${jsonEntries.size} entries (JSON fallback source).")
            } else {
                logs.add("[WARN] memories_history.json not found in main zip.")
            }

            if (histEntries.isEmpty() && jsonEntries.isEmpty()) {
                logs.add("[WARN] No metadata sources found — GPS/datetime enrichment unavailable.")
            }
        }

        val itemsByZip = mutableMapOf<String, List<HtmlMemoryEntry>>()
        val allHtmlEntries = mutableListOf<HtmlMemoryEntry>()
        val seenUuids = mutableMapOf<String, String>() // uuid -> first zip name
        var dupUuidCount = 0
        for (zipPath in zipFiles) {
            val htmlContent = readZipEntry(zipPath, "memories/memories.html") ?: continue
            val entries = ZipImportParser.parseMemoriesHtml(htmlContent)
            val zipName = zipPath.substringAfterLast('/').substringAfterLast('\\')
            for (entry in entries) {
                val prev = seenUuids.put(entry.uuid, zipName)
                if (prev != null) {
                    dupUuidCount++
                    if (dupUuidCount <= 5) {
                        logs.add("[WARN] Duplicate UUID ${entry.uuid.take(8)} in $zipName (first seen in $prev, date=${entry.date})")
                    }
                }
            }
            itemsByZip[zipPath] = entries
            allHtmlEntries.addAll(entries)
            val dateRange = if (entries.isNotEmpty()) {
                val sorted = entries.map { it.date }.sorted()
                "${sorted.first()} – ${sorted.last()}"
            } else "no entries"
            logs.add("[INFO] $zipName: ${entries.size} memories ($dateRange)")
        }
        if (dupUuidCount > 5) logs.add("[WARN] … and ${dupUuidCount - 5} more duplicate UUIDs")
        if (dupUuidCount > 0) logs.add("[WARN] $dupUuidCount duplicate UUIDs across zips — de-duplicated for correlation")
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

        // De-duplicate by UUID so positional alignment uses only the first-seen occurrence of each
        // memory. Duplicates across zips would otherwise corrupt the group ordering and assign the
        // wrong timestamp to every subsequent entry in that (date, type) group.
        val dedupedHtmlEntries = allHtmlEntries.distinctBy { it.uuid }

        val correlationMap = if (histEntries.isNotEmpty() || jsonEntries.isNotEmpty()) {
            MetadataCorrelator.correlate(histEntries, jsonEntries, dedupedHtmlEntries)
        } else emptyMap()
        val histMatched = correlationMap.values.count { it.source == CorrSource.Hist }
        val jsonMatched = correlationMap.values.count { it.source == CorrSource.Json }
        val unmatched = dedupedHtmlEntries.size - correlationMap.size
        logs.add("[INFO] Correlation: $histMatched exact (hist), $jsonMatched approx (JSON), $unmatched unmatched.")
        val corrWithGps = correlationMap.values.count { it.latitude != null }
        if (correlationMap.isNotEmpty()) {
            val gpsPct = corrWithGps * 100 / correlationMap.size
            logs.add("[INFO] GPS coverage: $corrWithGps / ${correlationMap.size} correlated entries ($gpsPct%).")
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

        if (runMetadata) {
            logs.add("[INFO] Writing metadata to extracted files…")
            val metaEntries = itemsByZip.values.flatten()
            val metaTotal = metaEntries.size
            var metaCount = 0
            var metaDone = 0
            var exactTimestampCount = 0
            var gpsWrittenCount = 0
            var dateMismatchCount = 0
            var fileNotFoundCount = 0
            val metaEta = EtaEstimator()
            // Track UUIDs already processed so duplicate entries (same snap saved multiple
            // times with different save-dates) reuse the corr from the first occurrence rather
            // than hitting the date-mismatch guard.
            val seenMetaUuids = mutableSetOf<String>()
            for (entry in metaEntries) {
                currentCoroutineContext().ensureActive()
                val outputPath = "$outDir/${entry.fileName}"
                val corr = correlationMap[entry.uuid]
                val isDuplicateEntry = !seenMetaUuids.add(entry.uuid)
                // For duplicate entries the UUID is the same snap saved on a different date;
                // the correlation from the first occurrence is still correct, so bypass the
                // date-match guard. For first-occurrence entries the guard catches genuine
                // positional-alignment errors and prevents writing the wrong timestamp.
                val validCorr = if (isDuplicateEntry) corr else corr?.takeIf { it.fullDateTime.take(10) == entry.date }
                if (!isDuplicateEntry && corr != null && validCorr == null) {
                    dateMismatchCount++
                    logs.add("[WARN] Date mismatch ${entry.uuid.take(8)}: filename=${entry.date} corr=${corr.fullDateTime.take(10)} — using filename date")
                }
                val dateTime = validCorr?.fullDateTime ?: "${entry.date} 00:00:00 UTC"
                val dateOk = mediaProcessor.writeDateMetadata(outputPath, dateTime)
                if (!dateOk) fileNotFoundCount++
                if (dateOk && validCorr != null && validCorr.source == CorrSource.Hist) exactTimestampCount++
                var gpsOk = false
                if (validCorr?.latitude != null && validCorr.longitude != null) {
                    gpsOk = mediaProcessor.writeGpsMetadata(outputPath, validCorr.latitude, validCorr.longitude, dateTime)
                    if (gpsOk) gpsWrittenCount++
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
            logs.add("[INFO] Metadata: $metaCount files tagged — exact timestamp: $exactTimestampCount, GPS: $gpsWrittenCount, date mismatches: $dateMismatchCount${if (fileNotFoundCount > 0) ", files not found: $fileNotFoundCount" else ""}.")
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
                onStart = { actual ->
                    combineTotal = actual.coerceAtLeast(1)
                    logs.add("[INFO] Found $actual overlay pairs. Combining…")
                    progressText = "Combining: 0 / $actual"
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

        val htmlPath = htmlFile ?: throw PipelineAbortException("No HTML/JSON file selected.")
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
