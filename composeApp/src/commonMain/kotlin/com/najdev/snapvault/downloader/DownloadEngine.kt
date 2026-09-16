package com.najdev.snapvault.downloader

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
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

// Per-item outcome: "downloaded", "skipped", or "error: <reason>".
data class DownloadResult(val item: MemoryItem, val status: String)

class DownloadEngine(
    private val client: HttpClient,
    private val fileSystem: FileSystem
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

        if (existingName != null) {
            val updated = item.copy(
                isDownloaded = true,
                downloadedPath = (outputFolderPath / existingName).toString()
            )
            return DownloadResult(updated, "skipped")
        }

        try {
            val response = if (item.isGet) {
                client.get(item.url) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                }
            } else {
                val parts = item.url.split("?")
                val postUrl = parts[0]
                val postData = if (parts.size > 1) parts[1] else ""
                client.post(postUrl) {
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
                    setBody(postData)
                }
            }

            if (response.status.value !in 200..299) {
                throw Exception("HTTP Error status: ${response.status}")
            }

            val contentType = response.headers[HttpHeaders.ContentType]
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
                        val read = bodyChannel.readAvailable(buffer, 0, buffer.size)
                        if (read > 0) {
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                // The resume check above ran against a listing taken before the request; a
                // file under this name now is one that arrived while the body was in flight.
                // Replacing it would be indistinguishable from an ordinary success, which is
                // how a clobbered download stayed invisible (D08).
                if (fileSystem.exists(filepath)) {
                    throw Exception("$filename was written by something else while this download was in flight")
                }
                fileSystem.atomicMove(tmpPath, filepath)
            } catch (e: Exception) {
                runCatching { fileSystem.delete(tmpPath) }
                throw e
            }

            val updated = item.copy(
                isDownloaded = true,
                downloadedPath = filepath.toString()
            )
            return DownloadResult(updated, "downloaded")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DownloadResult(item.copy(isDownloaded = false), "error: ${e.message}")
        }
    }

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
