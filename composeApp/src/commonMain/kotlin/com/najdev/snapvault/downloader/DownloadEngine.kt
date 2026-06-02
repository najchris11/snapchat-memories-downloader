package com.najdev.snapvault.downloader

import com.najdev.snapvault.model.MemoryItem
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

class DownloadEngine(
    private val client: HttpClient,
    private val fileSystem: FileSystem
) {
    private val _progressFlow = MutableSharedFlow<Pair<MemoryItem, String>>() // String can be "downloaded", "skipped", "error", or log message
    val progressFlow = _progressFlow.asSharedFlow()

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

    suspend fun downloadFile(item: MemoryItem, outputDir: String): MemoryItem {
        val outputFolderPath = outputDir.toPath()
        if (!fileSystem.exists(outputFolderPath)) {
            fileSystem.createDirectories(outputFolderPath)
        }

        // We check for files matching item.id in outputDir to support resuming
        val prefix = parseDateToFilenamePrefix(item.dateStr)
        val files = fileSystem.list(outputFolderPath)
        val alreadyDownloadedFile = files.find { file ->
            val filename = file.name
            (filename == "${item.id}.mp4" || filename == "${item.id}.jpg" || filename == "${item.id}.png" || filename == "${item.id}.zip") ||
            (prefix != null && (filename == "${prefix}_${item.id}.mp4" || filename == "${prefix}_${item.id}.jpg" || filename == "${prefix}_${item.id}.png" || filename == "${prefix}_${item.id}.zip"))
        }

        if (alreadyDownloadedFile != null) {
            val updated = item.copy(
                isDownloaded = true,
                downloadedPath = alreadyDownloadedFile.toString()
            )
            _progressFlow.emit(updated to "skipped")
            return updated
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

            val bodyChannel = response.bodyAsChannel()
            fileSystem.sink(filepath).buffer().use { sink ->
                val buffer = ByteArray(8192)
                while (!bodyChannel.isClosedForRead) {
                    val read = bodyChannel.readAvailable(buffer, 0, buffer.size)
                    if (read > 0) {
                        sink.write(buffer, 0, read)
                    }
                }
            }

            val updated = item.copy(
                isDownloaded = true,
                downloadedPath = filepath.toString()
            )
            _progressFlow.emit(updated to "downloaded")
            return updated
        } catch (e: Exception) {
            val updated = item.copy(isDownloaded = false)
            _progressFlow.emit(updated to "error: ${e.message}")
            return updated
        }
    }

    suspend fun downloadAll(
        items: List<MemoryItem>,
        outputDir: String,
        workers: Int
    ): List<MemoryItem> {
        val semaphore = Semaphore(workers)
        return coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        downloadFile(item, outputDir)
                    }
                }
            }.awaitAll()
        }
    }
}
