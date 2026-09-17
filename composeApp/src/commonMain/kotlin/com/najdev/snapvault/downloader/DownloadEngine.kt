package com.najdev.snapvault.downloader

import com.najdev.snapvault.ioDispatcher
import com.najdev.snapvault.model.MemoryItem
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.coroutines.CoroutineContext

// Per-item outcome: "downloaded", "skipped", or "error: <reason>".
data class DownloadResult(val item: MemoryItem, val status: String)

const val DEFAULT_STALL_TIMEOUT_MS = 60_000L

class DownloadEngine(
    private val client: HttpClient,
    private val fileSystem: FileSystem,
    private val stallTimeoutMillis: Long = DEFAULT_STALL_TIMEOUT_MS,
    // Where the body is streamed and the stall bound is measured. A parameter only because the
    // test filesystem is not thread-safe: tests pass a real-time context that runs one task at
    // a time, which keeps the wall-clock stall behaviour without racing FakeFileSystem.
    private val ioContext: CoroutineContext = ioDispatcher,
) {

    fun parseDateToFilenamePrefix(dateStr: String?): String? {
        if (dateStr == null) return null
        val cleaned = dateStr.trim()
        
        // Format 1: YYYY-MM-DD HH:MM:SS (UTC or similar) or YYYY-MM-DD
        val regex1 = Regex("""(\d{4})-(\d{2})-(\d{2})(?:\s+(\d{2}):(\d{2}):(\d{2}))?""")
        val match1 = regex1.find(cleaned)
        if (match1 != null) {
            val y = match1.groupValues[1]
            val m = match1.groupValues[2]
            val d = match1.groupValues[3]
            val h = match1.groupValues[4].takeIf { it.isNotEmpty() } ?: "00"
            val min = match1.groupValues[5].takeIf { it.isNotEmpty() } ?: "00"
            val s = match1.groupValues[6].takeIf { it.isNotEmpty() } ?: "00"
            // Keep the calendar date at the start of the filename in the same
            // YYYY-MM-DD_ form used by ZIP imports. Library can then display and
            // sort direct-download exports by capture date without relying on mtime.
            return "$y-$m-$d" + "_$h$min$s"
        }
        
        // Format 2: DD.MM.YYYY HH:MM:SS or DD.MM.YYYY
        val regex2 = Regex("""(\d{2})\.(\d{2})\.(\d{4})(?:\s+(\d{2}):(\d{2}):(\d{2}))?""")
        val match2 = regex2.find(cleaned)
        if (match2 != null) {
            val d = match2.groupValues[1]
            val m = match2.groupValues[2]
            val y = match2.groupValues[3]
            val h = match2.groupValues[4].takeIf { it.isNotEmpty() } ?: "00"
            val min = match2.groupValues[5].takeIf { it.isNotEmpty() } ?: "00"
            val s = match2.groupValues[6].takeIf { it.isNotEmpty() } ?: "00"
            return "$y-$m-$d" + "_$h$min$s"
        }
        
        return null
    }

    fun getFileExtensionFromUrl(url: String): String? {
        val urlPath = url.split("?")[0]
        val fileName = urlPath.split("/").lastOrNull() ?: ""
        if ("." in fileName) {
            val ext = "." + fileName.split(".").last().lowercase()
            if (ext in listOf(".mp4", ".jpg", ".jpeg", ".png", ".zip")) {
                return ext
            }
        }
        return null
    }

    fun getFileExtensionFromContentType(contentType: String): String {
        return when {
            contentType.contains("video") -> ".mp4"
            contentType.contains("image/jpeg") || contentType.contains("image/jpg") -> ".jpg"
            contentType.contains("image/png") -> ".png"
            contentType.contains("zip") -> ".zip"
            else -> ".mp4" // Fallback
        }
    }

    fun buildFilename(item: MemoryItem, contentType: String?): String {
        val prefix = parseDateToFilenamePrefix(item.dateStr)
        val ext = getFileExtensionFromUrl(item.url) 
            ?: contentType?.let { getFileExtensionFromContentType(it) } 
            ?: ".mp4"
            
        return if (prefix != null) {
            "${prefix}_${item.id}${ext}"
        } else {
            "${item.id}${ext}"
        }
    }

    private val resumableExtensions = listOf("mp4", "jpg", "jpeg", "png", "zip")

    /** A download that stopped making progress and was given up on rather than waited on. */
    private class DownloadStalledException(what: String, millis: Long) :
        Exception("no data for ${millis / 1000}s while $what — the connection stalled")

    /**
     * Whether a file under a media name is one a finished download would have left.
     *
     * Existence was the whole test before (D14), so an empty file or a saved error page under
     * the right name was skipped on every run from then on. This stays cheap — a metadata call
     * and a few leading bytes — because resume runs it across the whole library. It cannot tell
     * a *different* finished file from the right one; that needs a record of what was written,
     * and the metadata pass legitimately rewrites these files, so size is no evidence either.
     */
    private fun isFinishedFile(path: okio.Path): Boolean {
        val meta = fileSystem.metadataOrNull(path) ?: return false
        if (!meta.isRegularFile) return false
        if ((meta.size ?: 0L) <= 0L) return false
        return !looksLikeAWebPage(path)
    }

    /**
     * True when the file's first meaningful byte opens markup or JSON.
     *
     * Positive detection of an error body, deliberately not an allowlist of media signatures:
     * refusing everything unrecognised would fail real memories in container variants nobody
     * listed. No image, video or ZIP format SnapVault handles starts with `<` or `{`.
     */
    private fun looksLikeAWebPage(path: okio.Path): Boolean = runCatching {
        fileSystem.read(path) {
            val head = readByteArray(minOf(512L, fileSystem.metadata(path).size ?: 0L))
            val first = head.firstOrNull { it.toInt().toChar() !in " \t\r\n" }?.toInt()?.toChar()
            first == '<' || first == '{'
        }
    }.getOrDefault(false)

    private fun isWebPageContentType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type.startsWith("text/") || type == "application/json" || type == "application/xhtml+xml"
    }

    private suspend fun <T> withinStall(what: String, block: suspend () -> T): T =
        withTimeoutOrNull(stallTimeoutMillis) { block() }
            ?: throw DownloadStalledException(what, stallTimeoutMillis)

    // existingNames: pre-listed directory contents. downloadAll lists the directory once
    // and shares the set — per-item listing made resume checks O(n²) over the library.
    suspend fun downloadFile(item: MemoryItem, outputDir: String, existingNames: Set<String>? = null): DownloadResult {
        val outputFolderPath = outputDir.toPath()
        if (!fileSystem.exists(outputFolderPath)) {
            fileSystem.createDirectories(outputFolderPath)
        }

        // We check for files matching item.id in outputDir to support resuming
        val names = existingNames ?: fileSystem.list(outputFolderPath).map { it.name }.toSet()
        val prefix = parseDateToFilenamePrefix(item.dateStr)
        val existingName = resumableExtensions.map { "${item.id}.$it" }.firstOrNull { it in names }
            ?: prefix?.let { p ->
                val prefixes = listOf(p, p.replace("-", "")) // recognize exports from before the rename
                prefixes.firstNotNullOfOrNull { candidate ->
                    resumableExtensions.map { "${candidate}_${item.id}.$it" }.firstOrNull { it in names }
                }
            }

        // A name match that is not a finished file is a placeholder to replace, not a reason
        // to skip. It is left where it is until the replacement has been committed, so a
        // failed retry loses nothing that was not already lost.
        val placeholder = existingName?.let { outputFolderPath / it }?.takeUnless { isFinishedFile(it) }
        if (existingName != null && placeholder == null) {
            val updated = item.copy(
                isDownloaded = true,
                downloadedPath = (outputFolderPath / existingName).toString()
            )
            return DownloadResult(updated, "skipped")
        }

        try {
            val statement = if (item.isGet) {
                client.prepareGet(item.url) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                }
            } else {
                val parts = item.url.split("?")
                val postUrl = parts[0]
                val postData = if (parts.size > 1) parts[1] else ""
                client.preparePost(postUrl) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                    setBody(postData)
                }
            }

            // On the IO dispatcher: the body is written with blocking file calls, and the stall
            // bound has to measure the wall clock the network actually runs on.
            val filepath = withContext(ioContext) {
                streamToFile(statement, item, outputFolderPath, placeholder)
            }

            if (placeholder != null && placeholder != filepath) {
                // The good copy landed under a different name (extension or prefix changed);
                // the empty or error-page placeholder is now just litter with our name on it.
                runCatching { fileSystem.delete(placeholder) }
            }

            val updated = item.copy(
                isDownloaded = true,
                downloadedPath = filepath.toString()
            )
            return DownloadResult(updated, "downloaded")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DownloadResult(item.copy(isDownloaded = false), "error: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * Streams the response into a temp file, checks it is media, and commits it.
     *
     * Executed as a prepared statement rather than `client.get`: the plain call buffers the
     * whole body before returning, so a stalled body stalled *inside* it where no per-read
     * bound could reach, and a total timeout would kill slow but healthy video downloads.
     * Streaming lets the bound mean "no progress for this long" (D14) — and stops whole videos
     * being held in memory on the way to disk.
     */
    private suspend fun streamToFile(
        statement: HttpStatement,
        item: MemoryItem,
        outputFolderPath: okio.Path,
        placeholder: okio.Path?,
    ): okio.Path = coroutineScope {
        // The wait for headers is bounded separately: execute{} does not call back until they
        // arrive, so nothing inside the block can time that phase.
        val headersArrived = CompletableDeferred<Unit>()
        val watchdog = launch {
            withTimeoutOrNull(stallTimeoutMillis) { headersArrived.await() }
                ?: throw DownloadStalledException("waiting for the server to respond", stallTimeoutMillis)
        }

        val committed = statement.execute { response ->
            headersArrived.complete(Unit)

            if (response.status.value !in 200..299) {
                throw Exception("HTTP Error status: ${response.status}")
            }

            val contentType = response.headers[HttpHeaders.ContentType]
            if (isWebPageContentType(contentType)) throw expiredLink()

            val filename = buildFilename(item, contentType)
            val filepath = outputFolderPath / filename
            // Stream into a temp file and rename into place only when the body is fully
            // written — an interrupted download must never leave a truncated file under
            // the final name, because the resume check would skip it forever.
            val tmpPath = outputFolderPath / "$filename.part"

            try {
                val bodyChannel = response.bodyAsChannel()
                fileSystem.sink(tmpPath).buffer().use { sink ->
                    val buffer = ByteArray(8192)
                    while (!bodyChannel.isClosedForRead) {
                        val read = withinStall("downloading $filename") {
                            bodyChannel.readAvailable(buffer, 0, buffer.size)
                        }
                        if (read > 0) {
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                // A 2xx is not proof of media, and the header is only the server's claim: an
                // expired link can serve its page as anything. Refused before commit, so the
                // page never takes the name resume would trust.
                if (looksLikeAWebPage(tmpPath)) throw expiredLink()

                // The resume check above ran against a listing taken before the request; a
                // file under this name now is one that arrived while the body was in flight.
                // Replacing it would be indistinguishable from an ordinary success, which is
                // how a clobbered download stayed invisible (D08). The one file allowed to be
                // replaced is the placeholder resume already judged unfinished — and only if
                // it still is.
                val replacingPlaceholder = filepath == placeholder && !isFinishedFile(filepath)
                if (fileSystem.exists(filepath) && !replacingPlaceholder) {
                    throw IOException("$filename was written by something else while this download was in flight")
                }
                fileSystem.atomicMove(tmpPath, filepath)
            } catch (e: Throwable) {
                runCatching { fileSystem.delete(tmpPath) }
                throw e
            }
            filepath
        }
        watchdog.cancel()
        committed
    }

    private fun expiredLink() = Exception(
        "the link returned a web page instead of media — it has probably expired; " +
            "request a fresh export from Snapchat",
    )

    /**
     * How one export row relates to the file it wants.
     *
     * A Snapchat export repeats rows, and every repeat derives the same id from the same
     * `mid` and so builds the same filename. Scheduling them all was the D08 bug: with more
     * than one worker that is two responses streaming into one `<name>.part`, and whichever
     * finishes last committing the interleaving as though it were a file.
     */
    private sealed interface RowPlan {
        /** Fetches the file. Exactly one row per destination gets this. */
        data class Fetch(val followers: MutableList<Int> = mutableListOf()) : RowPlan

        /** The same link as a [Fetch] row: the same file, so it reports what that row reports. */
        data class Repeat(val leader: Int) : RowPlan

        /**
         * The same destination as a [Fetch] row but a *different* link.
         *
         * Not obviously the same media, so there is no winner to pick quietly — one of the
         * two would be dropped and the user would never learn which.
         */
        data class Conflict(val destination: String) : RowPlan
    }

    private fun planRows(items: List<MemoryItem>): List<RowPlan> {
        val leaderOf = mutableMapOf<String, Int>()
        val plans = mutableListOf<RowPlan>()
        items.forEachIndexed { index, item ->
            // The name as it can be known before the response arrives. Content-type can still
            // change the extension, which is why the commit is no-clobber as well.
            val destination = buildFilename(item, null)
            val leader = leaderOf[destination]
            plans += when {
                leader == null -> RowPlan.Fetch().also { leaderOf[destination] = index }
                items[leader].url == item.url ->
                    RowPlan.Repeat(leader).also { (plans[leader] as RowPlan.Fetch).followers += index }
                else -> RowPlan.Conflict(destination)
            }
        }
        return plans
    }

    // onProgress is invoked from a single consumer coroutine (never concurrently), so
    // callers can update UI state without their own synchronization.
    suspend fun downloadAll(
        items: List<MemoryItem>,
        outputDir: String,
        workers: Int,
        onProgress: ((DownloadResult) -> Unit)? = null,
    ): List<DownloadResult> {
        val outputFolderPath = outputDir.toPath()
        if (!fileSystem.exists(outputFolderPath)) {
            fileSystem.createDirectories(outputFolderPath)
        }
        // Snapshot the directory once for all resume checks. Files created during this run
        // need not appear in it, because planRows guarantees one writer per destination.
        val existingNames = fileSystem.list(outputFolderPath).map { it.name }.toSet()

        val plans = planRows(items)
        // Indexed rather than accumulated, so results come back in the caller's row order
        // however the workers interleave.
        val results = arrayOfNulls<DownloadResult>(items.size)
        val semaphore = Semaphore(workers)

        return coroutineScope {
            val channel = Channel<DownloadResult>(Channel.UNLIMITED)
            val consumer = launch {
                for (result in channel) onProgress?.invoke(result)
            }

            plans.forEachIndexed { index, plan ->
                if (plan !is RowPlan.Conflict) return@forEachIndexed
                val refused = DownloadResult(
                    items[index].copy(isDownloaded = false),
                    "error: another export entry already claims ${plan.destination}, " +
                        "and this row's link is different — it was not downloaded",
                )
                results[index] = refused
                channel.send(refused)
            }

            plans.mapIndexedNotNull { index, plan ->
                if (plan !is RowPlan.Fetch) return@mapIndexedNotNull null
                async {
                    semaphore.withPermit {
                        val result = downloadFile(items[index], outputDir, existingNames)
                        results[index] = result
                        channel.send(result)
                        // Repeats are the same file, reported as skips: every row still has to
                        // produce a result, because the progress total is the export's row
                        // count and a row that reports nothing leaves the bar short.
                        for (follower in plan.followers) {
                            val echo = DownloadResult(
                                items[follower].copy(
                                    isDownloaded = result.item.isDownloaded,
                                    downloadedPath = result.item.downloadedPath,
                                ),
                                if (result.status == "downloaded") "skipped" else result.status,
                            )
                            results[follower] = echo
                            channel.send(echo)
                        }
                    }
                }
            }.awaitAll()

            channel.close()
            consumer.join()
            results.requireNoNulls().toList()
        }
    }
}
