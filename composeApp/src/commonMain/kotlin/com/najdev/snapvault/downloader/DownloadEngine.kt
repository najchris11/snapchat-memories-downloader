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
import okio.Path
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
            return "$y$m$d" + "_$h$min$s"
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
            return "$y$m$d" + "_$h$min$s"
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
            ?: prefix?.let { p -> resumableExtensions.map { "${p}_${item.id}.$it" }.firstOrNull { it in names } }

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
        // Snapshot the directory once for all resume checks (items never collide on id,
        // so files created during this run don't need to appear in the snapshot).
        val existingNames = fileSystem.list(outputFolderPath).map { it.name }.toSet()

        val semaphore = Semaphore(workers)
        return coroutineScope {
            val channel = Channel<DownloadResult>(Channel.UNLIMITED)
            val consumer = launch {
                for (result in channel) onProgress?.invoke(result)
            }
            val results = items.map { item ->
                async {
                    semaphore.withPermit {
                        downloadFile(item, outputDir, existingNames).also { channel.send(it) }
                    }
                }
            }.awaitAll()
            channel.close()
            consumer.join()
            results
        }
    }
}
