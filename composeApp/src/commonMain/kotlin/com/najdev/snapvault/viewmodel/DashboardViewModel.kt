package com.najdev.snapvault.viewmodel

import androidx.compose.runtime.*
import com.najdev.snapvault.*
import com.najdev.snapvault.downloader.Deduplicator
import com.najdev.snapvault.downloader.DownloadEngine
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.model.FileMeta
import com.najdev.snapvault.model.MemoryItem
import com.najdev.snapvault.parser.*
import io.ktor.client.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
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
    // True once a completed (non-cancelled, non-aborted) run reported at least one
    // extraction/metadata/combine/dedupe failure — set only on the terminal success path.
    var hasWarnings by mutableStateOf(false)
        private set
    // True during a sub-phase that has real work in flight but no per-item signal to report
    // (the post-combine date-fallback batch, dedupe scanning) — the UI shows an animated
    // indeterminate ring instead of a progress value that would otherwise sit at a
    // misleadingly precise 0% for however long that sub-phase takes.
    var indeterminate by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var syncJob: Job? = null
    private val workerCount = computeWorkerCount()

    // Accumulates failure counts across every phase of the current run so the terminal
    // state can tell "clean success" from "reported success but something failed" apart —
    // see hasWarnings. Reset per run in startSync.
    private var pipelineFailureCount = 0

    private val logLock = SyncLock()

    // All log appends go through here: SnapshotStateList is not safe for unsynchronized
    // concurrent mutation, and lines originate from the UI thread (stopSync) as well as
    // pipeline coroutines.
    private fun log(message: String) {
        logLock.withLock { logs.add(message) }
    }

    // ── Picker actions ───────────────────────────────────────────────────────
    fun pickHtmlFile() = pickers.pickHtmlFile { it?.let { path -> htmlFile = path } }
    fun pickOutputFolder() = pickers.pickOutputFolder { it?.let { path -> downloadFolder = path } }
    fun pickZipFolder() = pickers.pickZipFolder { it?.let { path -> zipFolder = path; selectedZipFiles = emptyList() } }
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
        experimentalMetadataMatching: Boolean,
        runCombine: Boolean,
        runDedupe: Boolean,
        dryRun: Boolean,
    ) {
        // A double-click racing recomposition (before isRunning disables the button)
        // must not launch a second concurrent pipeline.
        if (syncJob?.isActive == true) return
        isRunning = true
        // logs.clear() races the previous job's still-in-flight log() calls during the
        // cancellation window (see stopSync/BUG-06) — both must go through logLock, since
        // SnapshotStateList isn't safe against an unsynchronized clear happening concurrently
        // with an append.
        logLock.withLock { logs.clear() }
        progress = 0f
        currentStep = 1
        speedText = "SPEED: --"
        etaText = "ETA: --"
        hasWarnings = false
        indeterminate = false
        pipelineFailureCount = 0

        // Captured so the finally block below can tell whether it's still the current run —
        // job.cancel() flips isActive false immediately, well before the cancelled
        // coroutine actually unwinds to its finally block. Without this, a new run started
        // in that window would have its isRunning = true clobbered back to false by the
        // stale job's belated cleanup (BUG-06).
        lateinit var thisJob: Job
        thisJob = scope.launch {
            try {
                val outDir = downloadFolder ?: throw PipelineAbortException("No output folder selected.")
                if (importMode == ImportMode.Zip) {
                    runZipPipeline(
                        outDir,
                        runMetadata,
                        experimentalMetadataMatching,
                        runCombine,
                        runDedupe,
                        dryRun,
                        workerCount,
                    )
                } else {
                    runLegacyPipeline(outDir, runDownload, runMetadata, runCombine, runDedupe, dryRun, workerCount)
                }
                progress = 1.0f
                indeterminate = false
                currentStep = 4
                speedText = "SPEED: --"
                etaText = "ETA: --"
                if (pipelineFailureCount > 0) {
                    hasWarnings = true
                    progressText = "Completed with warnings"
                    log("[WARN] Sync complete — $pipelineFailureCount failure(s) occurred, see warnings above.")
                } else {
                    progressText = "Pipeline Complete"
                    log("[SUCCESS] Sync complete!")
                }
            } catch (e: CancellationException) {
                log("[WARN] Sync cancelled by user.")
                progressText = "Cancelled"
                progress = 0f
                indeterminate = false
                speedText = "SPEED: --"
                etaText = "ETA: --"
                currentStep = 0
                throw e
            } catch (e: PipelineAbortException) {
                log("[ERROR] ${e.message}")
                progressText = "Failed"
                progress = 0f
                indeterminate = false
                speedText = "SPEED: --"
                etaText = "ETA: --"
                currentStep = 0
            } catch (e: Exception) {
                log("[ERROR] Pipeline failed: ${e.message}")
                progressText = "Failed"
                progress = 0f
                indeterminate = false
                speedText = "SPEED: --"
                etaText = "ETA: --"
                currentStep = 0
            } catch (e: Throwable) {
                // Plain `catch (e: Exception)` above doesn't match JVM Errors (e.g.
                // OutOfMemoryError) — those would otherwise skip every catch, propagate to
                // the SupervisorJob root with no installed CoroutineExceptionHandler, and
                // get silently printed to stderr while `finally` still reset the UI to idle.
                // That read as the pipeline hanging then vanishing with zero log output.
                log("[ERROR] Pipeline crashed: ${e::class.simpleName}: ${e.message}")
                progressText = "Failed"
                progress = 0f
                indeterminate = false
                speedText = "SPEED: --"
                etaText = "ETA: --"
                currentStep = 0
            } finally {
                // Runs only after all pipeline children have finished cancelling — the
                // Start button must not re-enable while ffmpeg/exiftool work is in flight.
                // Only clear isRunning if this is still the current job (BUG-06) — a stale
                // job whose cancellation is still unwinding after a newer run has already
                // started must not clobber that newer run's state.
                if (syncJob === thisJob) {
                    isRunning = false
                }
            }
        }
        syncJob = thisJob
    }

    fun stopSync() {
        val job = syncJob ?: return
        if (!job.isActive) return
        log("[WARN] Stopping — cancelling in-flight work…")
        progressText = "Stopping…"
        job.cancel()
        // isRunning flips in the pipeline's finally block once cancellation completes.
    }

    fun dispose() {
        scope.cancel()
        pickers.releaseAllSecurityAccess()
    }

    fun resetVaultIndex(): Boolean {
        // A running pipeline reads/writes vault_index.json at multiple points and holds its
        // own in-memory copy — deleting the on-disk file out from under it (e.g. from
        // Settings, reachable while a sync is in progress) risks losing entries the run is
        // about to persist. Model-level guard so this is safe regardless of which screen can
        // reach it (BUG-16).
        if (isRunning) return false
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
        experimentalMetadataMatching: Boolean,
        runCombine: Boolean,
        runDedupe: Boolean,
        dryRun: Boolean,
        workerCount: Int,
    ) {
        log("[INFO] Scanning for ZIP file(s)…")
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
        log("[INFO] Found ${zipFiles.size} zip file(s).")

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
            log("[INFO] $zipName: ${entries.size} memories, $parsedFileCount files parsed ($dateRange)")
            if (unmatchedCount > 0) {
                log("[WARN] $zipName: $unmatchedCount file(s) in memories/ skipped — unexpected filename format. Examples: ${unmatchedSamples.joinToString(", ")}")
            }
        }
        log("[INFO] Indexed $totalMemoryCount memories across ${itemsByZip.size} zip(s).")

        if (runMetadata && experimentalMetadataMatching) {
            log("[INFO] Precise time + GPS matching is on (default). GPS is only applied where a file's exact capture timestamp uniquely matches a memories_history.json record; ambiguous matches fall back to date-only metadata. Turn the toggle off on the Dashboard to force date-only tags for every file.")
        }

        if (AppBuildConfig.IS_DEBUG) {
            log("[DEBUG] Limiting to 2500 items (debug mode)")
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

        log("[INFO] Extracting media files…")
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
                log("[ERROR] ${result.fileName}: ${result.error}")
            } else if (result.skipped) {
                skippedCount++
            } else {
                extractedCount++
            }
            // Errors count toward completion — otherwise the bar stalls short of 100%.
            val done = extractedCount + skippedCount + extractErrorCount
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
        log(extractSummary)
        pipelineFailureCount += extractErrorCount
        currentStep = 2

        var pipelineCombinedCount = 0
        var pipelineCombineSkipped = 0
        var pipelineCombineErrors = 0

        if (runMetadata) {
            if (experimentalMetadataMatching) {
                // Read every zip's copy rather than stopping at the first: if a
                // multi-zip import has more than one archive carrying its own
                // memories_history.json (identical or not), silently using only one
                // and applying it to every zip's media would risk a cross-archive
                // timestamp collision misattributing GPS. Merging + deduping is safe —
                // identical copies collapse to one record, and any genuinely
                // conflicting record just becomes a same-key collision that
                // buildExperimentalZipMetadataPlan already resolves by omitting GPS
                // rather than guessing.
                val memoryJsonSources = withContext(ioDispatcher) {
                    zipFiles.mapNotNull { zipPath -> readZipEntryText(zipPath, "json/memories_history.json") }
                }

                if (memoryJsonSources.isEmpty()) {
                    log("[WARN] Experimental metadata matching requested, but no memories_history.json was found. Falling back to date-only ZIP metadata.")
                    writeZipDateMetadata(itemsByZip, outDir, workerCount, downloadedMeta)
                } else {
                    val records = memoryJsonSources.flatMap { parseZipMemoryRecords(it) }.distinct()
                    if (memoryJsonSources.size > 1) {
                        log("[INFO] Experimental metadata matching found memories_history.json in ${memoryJsonSources.size} zip(s); merged into ${records.size} distinct record(s).")
                    }

                    if (records.isEmpty()) {
                        log("[WARN] Experimental metadata matching found no usable memories; falling back to date-only ZIP metadata.")
                        writeZipDateMetadata(itemsByZip, outDir, workerCount, downloadedMeta)
                    } else {
                        val plan = buildExperimentalZipMetadataPlan(itemsByZip.values.flatten(), records)
                        plan.warnings.forEach { log("[WARN] $it") }
                        if (plan.targets.isEmpty()) {
                            log("[WARN] Experimental metadata matching produced no targets; falling back to date-only ZIP metadata.")
                            writeZipDateMetadata(itemsByZip, outDir, workerCount, downloadedMeta)
                        } else {
                            writeZipExperimentalMetadata(plan.targets, outDir, workerCount, downloadedMeta)
                        }
                    }
                }
            } else {
                writeZipDateMetadata(itemsByZip, outDir, workerCount, downloadedMeta)
            }
        }

        if (runCombine) {
            val (combined, skipped, errors) = runCombinePhase(outDir)
            pipelineCombinedCount = combined
            pipelineCombineSkipped = skipped
            pipelineCombineErrors = errors
        }

        if (runDedupe) runDeduplication(outDir, dryRun)

        runCatching {
            fileSystem.write(vaultIndexPath) {
                writeUtf8(Json.encodeToString<Map<String, FileMeta>>(downloadedMeta))
            }
            log("[INFO] Vault index saved (${downloadedMeta.size} entries).")
        }.onFailure { e -> log("[WARN] Could not write vault index: ${e.message}") }

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
        log(summary)
    }

    // ── Legacy pipeline ──────────────────────────────────────────────────────
    private suspend fun runLegacyPipeline(
        outDir: String,
        runDownload: Boolean,
        runMetadata: Boolean,
        runCombine: Boolean,
        runDedupe: Boolean,
        dryRun: Boolean,
        workerCount: Int,
    ) {
        progressText = "Reading memories…"
        log("[INFO] Starting pipeline sequence…")

        val htmlPath = htmlFile ?: throw PipelineAbortException("No HTML/JSON file selected.")
        val fileContent = fileSystem.read(htmlPath.toPath()) { readUtf8() }
        val isJson = htmlPath.endsWith(".json", ignoreCase = true)
        log("[INFO] Parsing memories history (${if (isJson) "JSON" else "HTML"})…")

        val parsed = if (isJson) HistoryParser.parseJson(fileContent) else HistoryParser.parse(fileContent)
        log("[INFO] File size: ${fileContent.length / 1024}KB — parsed ${parsed.size} items")
        if (parsed.isEmpty() && isJson)
            log("[DEBUG] JSON parsed but 0 items — verify the file contains a 'Saved Media' array")
        if (parsed.isEmpty() && !isJson) log("[DEBUG] ${HistoryParser.diagnose(fileContent)}")
        if (AppBuildConfig.IS_DEBUG) log("[DEBUG] Limiting to 2500 items (debug mode)")

        val items = if (AppBuildConfig.IS_DEBUG) parsed.take(2500) else parsed
        if (items.isEmpty()) throw PipelineAbortException("No items found. Use memories_history.json from your Snapchat export (mydata.snapchat.com).")

        val vaultIndexPath = "$outDir/vault_index.json".toPath()
        val downloadedMeta: MutableMap<String, FileMeta> = runCatching {
            Json.decodeFromString<Map<String, FileMeta>>(fileSystem.read(vaultIndexPath) { readUtf8() })
        }.getOrDefault(emptyMap()).toMutableMap()

        // Items with a file on disk after the download phase; used by the metadata pass.
        val presentItems = mutableListOf<MemoryItem>()

        if (runDownload) {
            log("[INFO] Downloading files in parallel…")
            val httpClient = HttpClient()
            var downloadedCount = 0
            var skippedCount = 0
            var errorCount = 0
            var done = 0
            val totalCount = items.size
            val dlEta = EtaEstimator()

            try {
                val downloader = DownloadEngine(httpClient, fileSystem)
                // onProgress is serialized by DownloadEngine's single consumer coroutine.
                val results = downloader.downloadAll(items, outDir, workerCount) { result ->
                    done++
                    dlEta.record(done)
                    progress = done.toFloat() / totalCount
                    progressText = "Downloading: $done of $totalCount files…"
                    val rate = dlEta.ratePerSec()
                    if (rate != null) {
                        speedText = "${rate.toInt().coerceAtLeast(1)} files/s"
                        etaText = when (val etaSec = dlEta.etaSeconds(totalCount, done)) {
                            null -> "ETA: --"
                            0L -> "ETA: done"
                            else -> "ETA: ${formatEta(etaSec)}"
                        }
                    }
                    val name = result.item.downloadedPath?.let { path ->
                        val lastSlash = path.lastIndexOfAny(charArrayOf('/', '\\'))
                        if (lastSlash != -1) path.substring(lastSlash + 1) else path
                    } ?: downloader.buildFilename(result.item, null)
                    when {
                        result.status == "downloaded" -> {
                            downloadedCount++
                            log("[DOWNLOADED] $name")
                            downloadedMeta[name] = FileMeta(hasGps = false, hasOverlay = false)
                        }
                        result.status == "skipped" -> {
                            skippedCount++
                            log("[SKIPPED] $name (already exists)")
                        }
                        else -> {
                            errorCount++
                            pipelineFailureCount++
                            log("[ERROR] Failed $name: ${result.status}")
                        }
                    }
                }
                presentItems.addAll(results.filter { it.item.downloadedPath != null }.map { it.item })
            } finally {
                httpClient.close()
            }
            speedText = "SPEED: --"
            etaText = "ETA: --"
            log("[INFO] Downloads: $downloadedCount new, $skippedCount existing${if (errorCount > 0) ", $errorCount errors" else ""}.")
        }

        currentStep = 2

        // Memories with overlays arrive as small .zip archives; extract them flat as
        // -main/-overlay pairs (legacy Python parity) so the combine phase can find them.
        progressText = "Extracting downloaded archives…"
        val extractedFiles = zipPipelineRunner.extractDownloadedArchives(outDir) { msg ->
            log("[WARN] [archive] $msg")
        }
        if (extractedFiles.isNotEmpty()) {
            log("[INFO] Extracted ${extractedFiles.size} file(s) from downloaded overlay archives.")
        }

        if (runMetadata) {
            legacyMetadataPass(presentItems, extractedFiles, downloadedMeta)
        }

        if (runCombine) {
            legacyCombinePass(outDir)
        }

        if (runDedupe) runDeduplication(outDir, dryRun)

        runCatching {
            fileSystem.write(vaultIndexPath) {
                writeUtf8(Json.encodeToString<Map<String, FileMeta>>(downloadedMeta))
            }
            log("[INFO] Vault index saved (${downloadedMeta.size} entries).")
        }.onFailure { e -> log("[WARN] Could not write vault index: ${e.message}") }
    }

    private suspend fun writeZipDateMetadata(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outDir: String,
        workerCount: Int,
        downloadedMeta: MutableMap<String, FileMeta>,
    ) {
        val metaEntries = itemsByZip.values.flatten()
        val metaTotal = metaEntries.size
        val byDate = metaEntries.groupBy { it.date }
        log("[INFO] Writing date metadata — ${metaTotal} files across ${byDate.size} date group(s)…")

        var metaCount = 0
        var metaDone = 0
        var metaFailCount = 0
        val metaEta = EtaEstimator()

        data class BatchResult(val tagged: Int, val entries: List<HtmlMemoryEntry>, val warnings: List<String>)

        val metaChannel = Channel<BatchResult>(Channel.UNLIMITED)
        val metaSemaphore = Semaphore(workerCount)

        coroutineScope {
            launch {
                for (r in metaChannel) {
                    r.warnings.forEach { log("[WARN] $it") }
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

            byDate.entries.map { (date, entries) ->
                async(ioDispatcher) {
                    currentCoroutineContext().ensureActive()
                    metaSemaphore.withPermit {
                        val paths = entries.map { "$outDir/${it.fileName}" }
                        val warnings = mutableListOf<String>()
                        val tagged = runInterruptibleCompat {
                            mediaProcessor.writeDateMetadataBatch(paths, date) { msg ->
                                warnings.add(msg)
                            }
                        }
                        metaChannel.send(BatchResult(tagged, entries, warnings))
                    }
                }
            }.awaitAll()

            metaChannel.close()
        }

        speedText = "SPEED: --"
        etaText = "ETA: --"
        log("[INFO] Metadata: $metaCount files tagged${if (metaFailCount > 0) ", $metaFailCount could not be tagged (see [WARN] lines above)" else ""}.")
        pipelineFailureCount += metaFailCount
    }

    // Snapchat sometimes stores overlay images as WebP but names them ".png"; exiftool
    // rejects them ("looks more like a RIFF") and the write fails. Checked by magic bytes,
    // not extension — matches the guard writeDateMetadataBatch.runGroup already applies on
    // the date-only path, so both metadata paths skip these before spawning exiftool.
    // internal (not private) so DashboardViewModelTest can verify it directly.
    internal fun isRiffMislabeledAsPng(path: String): Boolean {
        if (!path.endsWith(".png", ignoreCase = true)) return false
        return try {
            fileSystem.source(path.toPath()).buffer().use { source ->
                val header = source.readByteArray(4L)
                header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun writeZipExperimentalMetadata(
        targets: List<ZipMetadataTarget>,
        outDir: String,
        workerCount: Int,
        downloadedMeta: MutableMap<String, FileMeta>,
    ) {
        log("[INFO] Writing experimental ZIP metadata — ${targets.size} files (date + GPS where available)…")
        progress = 0f
        progressText = "Metadata: 0 / ${targets.size}"

        var gpsCount = 0
        var dateCount = 0
        var failCount = 0
        var riffSkippedCount = 0
        val failSamples = mutableListOf<String>()
        val riffSkippedSamples = mutableListOf<String>()
        var done = 0
        val metaEta = EtaEstimator()

        data class Result(val target: ZipMetadataTarget, val outcome: String)

        val channel = Channel<Result>(Channel.UNLIMITED)
        val semaphore = Semaphore(workerCount)

        coroutineScope {
            launch {
                for (result in channel) {
                    when (result.outcome) {
                        "gps" -> {
                            gpsCount++
                            downloadedMeta[result.target.fileName] = FileMeta(hasGps = true, hasOverlay = result.target.hasOverlay)
                        }
                        "date" -> {
                            dateCount++
                            downloadedMeta[result.target.fileName] = FileMeta(hasGps = false, hasOverlay = result.target.hasOverlay)
                        }
                        "skipped_riff" -> {
                            riffSkippedCount++
                            if (riffSkippedSamples.size < 5) riffSkippedSamples.add(result.target.fileName)
                        }
                        else -> {
                            failCount++
                            if (failSamples.size < 5) failSamples.add(result.target.fileName)
                        }
                    }
                    done++
                    metaEta.record(done)
                    progress = done.toFloat() / targets.size.coerceAtLeast(1)
                    progressText = "Metadata: $done / ${targets.size}"
                    val rate = metaEta.ratePerSec()
                    if (rate != null) {
                        speedText = "${rate.toInt().coerceAtLeast(1)} files/s"
                        etaText = when (val etaSec = metaEta.etaSeconds(targets.size, done)) {
                            null -> "ETA: --"
                            0L -> "ETA: done"
                            else -> "ETA: ${formatEta(etaSec)}"
                        }
                    }
                }
            }

            targets.map { target ->
                async(ioDispatcher) {
                    currentCoroutineContext().ensureActive()
                    semaphore.withPermit {
                        val path = "$outDir/${target.fileName}"
                        val outcome = if (isRiffMislabeledAsPng(path)) {
                            "skipped_riff"
                        } else {
                            runInterruptibleCompat {
                                if (target.latitude != null && target.longitude != null) {
                                    if (mediaProcessor.writeGpsMetadata(path, target.latitude, target.longitude, target.dateStr)) "gps" else "fail"
                                } else {
                                    if (mediaProcessor.writeDateMetadata(path, target.dateStr)) "date" else "fail"
                                }
                            }
                        }
                        channel.send(Result(target, outcome))
                    }
                }
            }.awaitAll()

            channel.close()
        }

        speedText = "SPEED: --"
        etaText = "ETA: --"
        if (riffSkippedCount > 0) {
            log("[INFO] Skipped $riffSkippedCount overlay(s) stored as WebP but named .png — exiftool would reject them; harmless, they're consumed by the combine phase. Examples: ${riffSkippedSamples.joinToString(", ")}")
        }
        if (failCount > 0) {
            log("[WARN] Metadata: $failCount file(s) failed to tag. Examples: ${failSamples.joinToString(", ")}")
        }
        log(
            "[INFO] Metadata: ${gpsCount + dateCount} tagged ($gpsCount with GPS)" +
                (if (riffSkippedCount > 0) ", $riffSkippedCount skipped (mislabeled)" else "") +
                (if (failCount > 0) ", $failCount failed" else "") +
                "."
        )
        pipelineFailureCount += failCount
    }

    // Shared by both pipelines: combines every -main/-overlay pair in outDir.
    // Returns (combined, skipped, errors).
    private suspend fun runCombinePhase(outDir: String): Triple<Int, Int, Int> {
        var combinedCount = 0
        var combineErrorCount = 0
        var combineSkippedCount = 0
        var combineDone = 0
        var combineTotal = 1
        var summaryLogged = false
        val combineEta = EtaEstimator()
        var combineEncoder: String? = null
        progress = 0f
        indeterminate = false
        progressText = "Combining overlays…"

        // The per-pair combine summary used to be logged after the whole combine phase
        // returned — which is after the date-fallback sub-phase below, so it read as
        // describing work that had finished several minutes earlier (BUG-14). Logging it
        // the moment the per-pair loop itself finishes keeps the log chronological.
        fun logCombineSummaryOnce() {
            if (summaryLogged) return
            summaryLogged = true
            val combineSummary = buildString {
                append("[INFO] Combined $combinedCount overlay pairs")
                if (combineSkippedCount > 0) append(", $combineSkippedCount skipped (unsupported format)")
                if (combineErrorCount > 0) append(", $combineErrorCount errors")
                append(".")
            }
            log(combineSummary)
            pipelineFailureCount += combineErrorCount
            mediaProcessor.videoEncodeStats()?.let { stats ->
                if (stats.hardware + stats.software > 0) {
                    log("[INFO] Video encodes: ${stats.hardware} hardware, ${stats.software} software.")
                    // A hardware encoder was active at start but some files still went software.
                    if (combineEncoder != null && stats.software > 0) {
                        log("[WARN] ${stats.software} video(s) fell back to software encoding (see stderr for per-file reasons).")
                    }
                }
            }
        }

        zipPipelineRunner.combineAll(
            outDir,
            deleteOriginals = true,
            workerCount = workerCount,
            onMetaError = { msg -> log("[WARN] $msg") },
            onStart = { actual ->
                combineTotal = actual.coerceAtLeast(1)
                mediaProcessor.resetVideoEncodeStats()
                // Probe-backed: names an encoder only if a real test encode succeeded.
                combineEncoder = mediaProcessor.activeVideoEncoder()
                val encoderLabel = combineEncoder?.let { "hardware ($it)" } ?: "software (libx264)"
                log("[INFO] Found $actual overlay pairs. Combining… [video encoder: $encoderLabel]")
                progressText = "Combining: 0 / $actual"
                // Nothing to combine — no onProgress callback will ever fire to close this out.
                if (actual == 0) {
                    progress = 1f
                    logCombineSummaryOnce()
                }
            },
            onMetaStart = { total ->
                // The batch below reports no per-file progress; show that honestly instead
                // of a precise-looking 0% (BUG-04) — and reset stale combine-phase metrics
                // (BUG-04's "stale ETA/pairs-per-sec" complaint) the moment this sub-phase
                // starts, not whenever the whole combine phase eventually returns.
                log("[INFO] Tagging $total combined file(s) with date metadata…")
                progressText = "Tagging combined files…"
                progress = 0f
                indeterminate = true
                speedText = "SPEED: --"
                etaText = "ETA: --"
            }
        ) { result ->
            result.warnings.forEach { log("[WARN] $it") }
            when {
                result.status == "combined" -> combinedCount++
                result.status.startsWith("skipped:") -> combineSkippedCount++
                result.status.startsWith("error") -> {
                    combineErrorCount++
                    log("[ERROR] Overlay combine ${result.uuid.take(8)}: ${result.status}")
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
            if (combineDone == combineTotal) {
                speedText = "SPEED: --"
                etaText = "ETA: --"
                logCombineSummaryOnce()
            }
        }
        // Phase completion write (BUG-04): whether or not the date-fallback sub-phase ran,
        // the combine phase as a whole is done here — don't leave the ring parked at 0%.
        indeterminate = false
        progress = 1f
        speedText = "SPEED: --"
        etaText = "ETA: --"
        return Triple(combinedCount, combineSkippedCount, combineErrorCount)
    }

    private suspend fun legacyCombinePass(outDir: String) {
        runCombinePhase(outDir)
    }

    //META legacy metadata pass: full date+TIME from the history file's dateStr, plus GPS
    //META when the history file provided coordinates — richer than the ZIP path's date-only tags.
    //META Overlay files are skipped (combine consumes them; the combined output inherits the
    //META main file's tags via exiftool copy) — matches the legacy Python behavior.
    private suspend fun legacyMetadataPass(
        presentItems: List<MemoryItem>,
        extractedFiles: List<String>,
        downloadedMeta: MutableMap<String, FileMeta>,
    ) {
        data class MetaTarget(val path: String, val dateStr: String?, val lat: Double?, val lon: Double?)

        fun leafName(path: String): String {
            val lastSlash = path.lastIndexOfAny(charArrayOf('/', '\\'))
            return if (lastSlash != -1) path.substring(lastSlash + 1) else path
        }

        // Owner lookup for extracted archive contents: "<base>-main.jpg" came from "<base>.zip".
        val itemByArchiveBase = presentItems
            .filter { it.downloadedPath?.endsWith(".zip", ignoreCase = true) == true }
            .associateBy { leafName(it.downloadedPath!!).removeSuffix(".zip") }

        val targets = mutableListOf<MetaTarget>()
        presentItems
            .filter { it.downloadedPath != null && !it.downloadedPath.endsWith(".zip", ignoreCase = true) }
            .forEach { targets.add(MetaTarget(it.downloadedPath!!, it.dateStr, it.latitude, it.longitude)) }
        for (path in extractedFiles) {
            val name = leafName(path)
            if ("-overlay." in name) continue
            val base = name.substringBefore("-main.").takeIf { it != name }
            val owner = base?.let { itemByArchiveBase[it] }
            targets.add(MetaTarget(path, owner?.dateStr, owner?.latitude, owner?.longitude))
        }

        if (targets.isEmpty()) return
        log("[INFO] Writing metadata (date + GPS where available) to ${targets.size} file(s)…")
        progress = 0f
        progressText = "Metadata: 0 / ${targets.size}"

        var gpsCount = 0
        var dateCount = 0
        var failCount = 0
        var noDateCount = 0
        var done = 0
        val metaEta = EtaEstimator()

        // outcome: "gps", "date", "fail", "nodate"
        val channel = Channel<Pair<MetaTarget, String>>(Channel.UNLIMITED)
        val semaphore = Semaphore(workerCount)

        coroutineScope {
            // Single consumer — serializes counters, UI state, and vault index updates.
            launch {
                for ((target, outcome) in channel) {
                    when (outcome) {
                        "gps" -> {
                            gpsCount++
                            downloadedMeta[leafName(target.path)] = FileMeta(hasGps = true, hasOverlay = false)
                        }
                        "date" -> dateCount++
                        "fail" -> failCount++
                        "nodate" -> noDateCount++
                    }
                    done++
                    metaEta.record(done)
                    progress = done.toFloat() / targets.size
                    progressText = "Metadata: $done / ${targets.size}"
                    val rate = metaEta.ratePerSec()
                    if (rate != null) {
                        speedText = "${rate.toInt().coerceAtLeast(1)} files/s"
                        etaText = when (val etaSec = metaEta.etaSeconds(targets.size, done)) {
                            null -> "ETA: --"
                            0L -> "ETA: done"
                            else -> "ETA: ${formatEta(etaSec)}"
                        }
                    }
                }
            }

            targets.map { target ->
                async(ioDispatcher) {
                    semaphore.withPermit {
                        val outcome = runInterruptibleCompat {
                            when {
                                target.lat != null && target.lon != null ->
                                    //META GPS + full datetime in one exiftool call
                                    if (mediaProcessor.writeGpsMetadata(target.path, target.lat, target.lon, target.dateStr)) "gps" else "fail"
                                target.dateStr != null ->
                                    if (mediaProcessor.writeDateMetadata(target.path, target.dateStr)) "date" else "fail"
                                else -> "nodate"
                            }
                        }
                        channel.send(target to outcome)
                    }
                }
            }.awaitAll()

            channel.close()
        }

        speedText = "SPEED: --"
        etaText = "ETA: --"
        val summary = buildString {
            append("[INFO] Metadata: ${gpsCount + dateCount} tagged ($gpsCount with GPS)")
            if (noDateCount > 0) append(", $noDateCount had no date in the history file")
            if (failCount > 0) append(", $failCount failed")
            append(".")
        }
        log(summary)
        pipelineFailureCount += failCount
    }

    private suspend fun runDeduplication(outDir: String, dryRun: Boolean) {
        log("[INFO] Scanning for duplicate files…${if (dryRun) " (dry run — nothing will be deleted)" else ""}")
        progressText = "Deduplicating: Scanning…"
        speedText = "SPEED: --"
        etaText = "ETA: --"
        // Hashing/deletion runs as one blocking call with no per-file callback — show that
        // honestly (BUG-04) instead of parking the ring at whatever value the prior phase
        // left it on.
        indeterminate = true
        val deduplicator = Deduplicator(fileSystem)
        val results = withContext(ioDispatcher) {
            deduplicator.deduplicateFolder(outDir.toPath(), dryRun)
        }
        indeterminate = false
        progress = 1f
        if (results.isEmpty()) {
            log("[INFO] No duplicate files found.")
        } else {
            val totalDeleted = results.sumOf { it.deletedFiles.size }
            val totalFailed = results.sumOf { it.failedFiles.size }
            progressText = if (dryRun) "Deduplicating: Found $totalDeleted duplicates" else "Deduplicating: Removed $totalDeleted files"
            results.forEach { res ->
                if (dryRun) {
                    log("[WARN] Dry run — would keep ${res.keptFile} and delete: ${res.deletedFiles.joinToString()}")
                } else {
                    if (res.deletedFiles.isNotEmpty()) {
                        log("[DELETED DUPES] Kept ${res.keptFile}, deleted: ${res.deletedFiles.joinToString()}")
                    }
                    if (res.failedFiles.isNotEmpty()) {
                        log("[WARN] Kept ${res.keptFile}, but could not delete (still on disk): ${res.failedFiles.joinToString()}")
                    }
                }
            }
            if (dryRun) {
                log("[INFO] Dry run complete — $totalDeleted duplicate file(s) would be deleted. Disable dry run to apply.")
            } else if (totalFailed > 0) {
                pipelineFailureCount += totalFailed
                log("[WARN] Deduplication: $totalDeleted file(s) deleted, $totalFailed could not be deleted.")
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
