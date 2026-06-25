package com.najdev.snapvault

import com.najdev.snapvault.downloader.Deduplicator
import com.najdev.snapvault.downloader.DownloadEngine
import com.najdev.snapvault.metadata.DesktopMediaProcessor
import com.najdev.snapvault.parser.HistoryParser
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File

fun main() {
    println("==================================================")
    println("       SnapVault - Lightweight CLI Runner       ")
    println("==================================================")
    println()

    val fileSystem = FileSystem.SYSTEM
    
    // Look for memories_history.html in project root or ./test-run/
    val candidatePaths = listOf(
        "memories_history.html",
        "test-run/memories_history.html"
    )
    
    var htmlFile: File? = null
    for (path in candidatePaths) {
        val f = File(path)
        if (f.exists() && f.isFile) {
            htmlFile = f
            break
        }
    }

    if (htmlFile == null) {
        println("[ERROR] 'memories_history.html' not found!")
        println("Please drop your 'memories_history.html' file into the project root directory")
        println("or create a folder called 'test-run' and place it there.")
        println("Candidate locations searched:")
        candidatePaths.forEach { println(" - ${File(it).absolutePath}") }
        return
    }

    println("[OK] Found history file at: ${htmlFile.absolutePath}")
    
    val htmlContent = htmlFile.readText(Charsets.UTF_8)
    println("[INFO] Parsing HTML content...")
    val items = HistoryParser.parse(htmlContent)
    println("[OK] Parsed ${items.size} memory history items.")
    if (items.isEmpty()) {
        println("[WARN] No memories found in HTML file. Exiting.")
        return
    }

    val outputDir = "test-run/downloads"
    println("[INFO] Media will be downloaded to: ${File(outputDir).absolutePath}")

    val mediaProcessor = DesktopMediaProcessor()
    val hasExif = mediaProcessor.checkExifTool()
    val hasFFmpeg = mediaProcessor.checkFFmpeg()
    
    println("[INFO] System dependencies check:")
    println(" - ExifTool: ${if (hasExif) "AVAILABLE (Exif writing enabled)" else "MISSING (No Exif will be written)"}")
    println(" - FFmpeg:   ${if (hasFFmpeg) "AVAILABLE (Video overlay combining enabled)" else "MISSING (No video overlay combining)"}")
    println()

    val httpClient = HttpClient(CIO)
    val downloader = DownloadEngine(httpClient, fileSystem)

    // Listen to download progress
    val job = downloader.progressFlow.onEach { (item, status) ->
        val name = item.downloadedPath?.let { path ->
            val lastSlash = path.lastIndexOfAny(charArrayOf('/', '\\'))
            if (lastSlash != -1) path.substring(lastSlash + 1) else path
        } ?: downloader.buildFilename(item, null)
        when {
            status == "downloaded" -> {
                println("[DOWNLOADED] $name")
                
                // Write GPS metadata if available
                if (hasExif && item.latitude != null && item.longitude != null && item.downloadedPath != null) {
                    val ok = mediaProcessor.writeGpsMetadata(item.downloadedPath, item.latitude, item.longitude, item.dateStr)
                    if (ok) {
                        println("   -> [METADATA] EXIF GPS written successfully.")
                    } else {
                        println("   -> [METADATA] Failed to write EXIF GPS.")
                    }
                }
            }
            status == "skipped" -> println("[SKIPPED] $name (already exists)")
            else -> {
                if (status.startsWith("error")) {
                    println("[ERROR] Failed downloading ${item.id}: $status")
                }
            }
        }
    }.launchIn(CoroutineScope(Dispatchers.Default))

    println("[INFO] Starting parallel downloads (Workers: 5)...")
    val resultItems = runBlocking {
        downloader.downloadAll(items, outputDir, workers = 5)
    }

    job.cancel()
    httpClient.close()

    val downloadedCount = resultItems.count { it.isDownloaded }
    println()
    println("[OK] Download phase complete: $downloadedCount of ${items.size} files processed.")

    // Run Deduplication
    println("[INFO] Running duplicate cleanup...")
    val deduplicator = Deduplicator(fileSystem)
    val dedupResults = deduplicator.deduplicateAll(outputDir, dryRun = false)
    if (dedupResults.isEmpty()) {
        println("[OK] No duplicate files found.")
    } else {
        println("[OK] Cleaned up duplicates:")
        dedupResults.forEach { res ->
            println(" - In folder ${res.folder}, kept ${res.keptFile}, deleted: ${res.deletedFiles.joinToString()}")
        }
    }

    println()
    println("==================================================")
    println("       Pipeline complete. All operations done.    ")
    println("==================================================")
}
