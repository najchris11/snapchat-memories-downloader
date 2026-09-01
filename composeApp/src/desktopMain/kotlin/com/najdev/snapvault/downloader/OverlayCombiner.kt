package com.najdev.snapvault.downloader

import com.najdev.snapvault.BinaryExtractor
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.metadata.SupportedMediaExtensions
import com.najdev.snapvault.waitForOrKill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class OverlayCombiner(private val mediaProcessor: MediaProcessor) {

    // ffmpeg spawns multi-threaded processes that saturate the CPU on their own;
    // serialize them so only one runs at a time regardless of workerCount.
    private val ffmpegSemaphore = Semaphore(1)

    data class OverlayPair(
        val mainFile: File,
        val overlayFile: File,
        val outputFile: File,
        val isVideo: Boolean
    )

    fun findPairs(outputDir: String): List<OverlayPair> {
        val dir = File(outputDir)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val allFiles = dir.listFiles() ?: return emptyList()

        // Stem-based matching: "2017-07-13_UUID" is the stem shared by the main and overlay
        // files for the same memory.  No UUID parsing is required — this is a physical
        // file-matching operation that naturally handles duplicate UUIDs (different date
        // prefixes produce different stems) and any future filename format changes.
        val overlayByStem = allFiles
            .filter { "-overlay." in it.name }
            .mapNotNull { f ->
                val stem = f.name.substringBefore("-overlay.").takeIf { it != f.name && it.isNotEmpty() }
                stem?.let { it to f }
            }
            .toMap()

        return allFiles
            .filter { "-main." in it.name }
            .mapNotNull { mainFile ->
                val stem = mainFile.name.substringBefore("-main.")
                    .takeIf { it != mainFile.name && it.isNotEmpty() } ?: return@mapNotNull null
                val overlayFile = overlayByStem[stem] ?: return@mapNotNull null
                val extLc = mainFile.extension.lowercase()
                // Shared with DesktopMediaProcessor/MediaScanner (BUG-18) — this used to be
                // its own hand-maintained list missing "m4v", so an .m4v pair would be
                // misclassified as an image combine attempt instead of a video one.
                val isVideo = extLc in SupportedMediaExtensions.VIDEO
                // HEIC/WebP cannot be written by Java ImageIO; the FFmpeg fallback outputs JPEG.
                // Set the output extension to .jpg upfront so the output path is always correct.
                val outputExt = if (!isVideo && extLc in setOf("heic", "heif", "webp")) "jpg" else mainFile.extension
                OverlayPair(
                    mainFile = mainFile,
                    overlayFile = overlayFile,
                    outputFile = File(outputDir, "$stem.$outputExt"),
                    isVideo = isVideo
                )
            }
    }

    suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit = {},
        onMetaStart: (total: Int) -> Unit = {},
        onMetaError: ((String) -> Unit)? = null,
        onProgress: (CombineResult) -> Unit
    ) {
        val pairs = findPairs(outputDir)
        onStart(pairs.size)
        val channel = Channel<CombineResult>(Channel.UNLIMITED)
        val semaphore = Semaphore(workerCount)
        // needsDateFallback is the subset of successful pairs whose combined output still has
        // no real capture timestamp after copyExif/TagsFromFile ran — see BUG-01: this batch
        // used to run unconditionally and clobber the precise time the metadata phase had
        // already propagated onto every combined file with "$dateOnly 00:00:00".
        val needsDateFallback = mutableListOf<OverlayPair>()
        val lock = Any()

        coroutineScope {
            launch {
                for (result in channel) onProgress(result)
            }

            pairs.map { pair ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val uuid = extractUuid(pair.mainFile.name) ?: pair.mainFile.nameWithoutExtension
                        // Warnings are collected per-pair and shipped inside the result so the
                        // single channel consumer is the only thread touching caller state.
                        val warnings = mutableListOf<String>()
                        val status = processPair(pair, deleteOriginals) { msg -> warnings.add("[combine] $msg") }
                        if (status == "combined" && !hasDateTag(pair.outputFile.absolutePath, pair.isVideo)) {
                            synchronized(lock) { needsDateFallback.add(pair) }
                        }
                        channel.send(CombineResult(uuid, pair.outputFile.absolutePath, status, warnings))
                    }
                }
            }.awaitAll()

            channel.close()
        }

        //META fallback date write: only for combined outputs that came out of the copy step
        //META above with no real capture timestamp (metadata phase was off, or never matched
        //META a timestamp for this file) — groups those by the YYYY-MM-DD prefix in the
        //META filename, same as the ZIP pipeline's date-only path.
        if (needsDateFallback.isNotEmpty()) {
            val dateGroups = needsDateFallback
                .groupBy { it.mainFile.name.substringBefore('_').takeIf { d -> d.length == 10 } }
                .filterKeys { it != null }
            onMetaStart(needsDateFallback.size)
            withContext(Dispatchers.IO) {
                dateGroups.forEach { (date, datePairs) ->
                    val d = date ?: return@forEach
                    runInterruptible {
                        mediaProcessor.writeDateMetadataBatch(
                            datePairs.map { it.outputFile.absolutePath },
                            d,
                            onMetaError
                        )
                    }
                }
            }
        }
    }

    private suspend fun processPair(pair: OverlayPair, deleteOriginals: Boolean, onWarning: (String) -> Unit = {}): String {
        return try {
            // runInterruptible lets pipeline cancellation interrupt the blocking ffmpeg/ImageIO
            // work; the process helpers kill the child on interrupt (see waitForOrKill).
            val err = if (pair.isVideo) {
                ffmpegSemaphore.withPermit {
                    runInterruptible {
                        if (mediaProcessor.combineVideoWithOverlay(
                                pair.mainFile.absolutePath,
                                pair.overlayFile.absolutePath,
                                pair.outputFile.absolutePath
                            )
                        ) null else "video combine failed"
                    }
                }
            } else {
                // Two-tier: ImageIO (fast, handles JPG/PNG) → FFmpeg (universal fallback)
                val imageIoErr = runInterruptible { combineImages(pair.mainFile, pair.overlayFile, pair.outputFile) }
                imageIoErr?.let {
                    ffmpegSemaphore.withPermit {
                        runInterruptible {
                            combineImagesFfmpeg(pair.mainFile, pair.overlayFile, pair.outputFile)
                        }
                    }
                }
            }

            if (err != null) return if (err.startsWith("skipped:")) err else "error: $err"

            // Verify output was actually written before destroying originals
            if (!pair.outputFile.exists() || pair.outputFile.length() == 0L) {
                return "error: output missing after combine — ${pair.outputFile.name}"
            }

            // Preserve the original's metadata on the combined image (the video path does
            // this inside combineVideoWithOverlay); must happen before originals are deleted.
            if (!pair.isVideo) {
                val copied = runInterruptible { copyExif(pair.mainFile.absolutePath, pair.outputFile.absolutePath) }
                if (!copied) onWarning("could not copy metadata onto combined output: ${pair.outputFile.name}")
            }

            if (deleteOriginals) {
                if (!pair.mainFile.delete()) onWarning("could not delete main: ${pair.mainFile.name}")
                if (!pair.overlayFile.delete()) onWarning("could not delete overlay: ${pair.overlayFile.name}")
            }

            "combined"
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    // Returns null on success or a reason string on failure.
    private fun combineImages(mainFile: File, overlayFile: File, outputFile: File): String? {
        return try {
            val mainImg = ImageIO.read(mainFile)
                ?: return "cannot read main (${mainFile.length()}B, detected: ${detectFormat(mainFile)})"
            val overlayImg = ImageIO.read(overlayFile)
                ?: return "cannot read overlay (${overlayFile.length()}B, detected: ${detectFormat(overlayFile)})"

            val w = mainImg.width
            val h = mainImg.height
            val combined = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = combined.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(mainImg, 0, 0, null)
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER)
            g.drawImage(overlayImg, 0, 0, w, h, null)
            g.dispose()

            val format = if (outputFile.extension.lowercase() == "png") "PNG" else "JPEG"
            val wrote = ImageIO.write(combined, format, outputFile)
            if (!wrote) return "no ImageIO writer for format=$format (ext=${outputFile.extension})"
            null
        } catch (e: Exception) {
            e.message ?: "unknown exception"
        }
    }

    // FFmpeg fallback: handles HEIC, WebP, and any other format ImageIO cannot decode.
    // Returns null on success, a reason string on failure, or "skipped:…" if FFmpeg is absent.
    private fun combineImagesFfmpeg(mainFile: File, overlayFile: File, outputFile: File): String? {
        val ffmpegPath = BinaryExtractor.checkCommand("ffmpeg")
            ?: return "skipped: install FFmpeg to combine ${mainFile.extension.uppercase()} overlays"
        // Same scale2ref+overlay filter used for video, but -frames:v 1 produces a single image.
        val filterComplex = "[1:v][0:v]scale2ref[ovr][base];[base][ovr]overlay=0:0:format=auto"
        val args = listOf(
            ffmpegPath, "-y",
            "-i", mainFile.absolutePath,
            "-i", overlayFile.absolutePath,
            "-filter_complex", filterComplex,
            "-frames:v", "1",
            "-q:v", "2",
            outputFile.absolutePath
        )
        return try {
            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitForOrKill()
            if (exitCode == 0) null
            else "ffmpeg exit $exitCode${if (output.isNotBlank()) ": ${output.takeLast(120)}" else ""}"
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "ffmpeg exception"
        }
    }

    private fun detectFormat(file: File): String {
        return try {
            val h = file.inputStream().use { it.readNBytes(12) }
            when {
                h.isEmpty() -> "empty"
                h.size >= 2 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() -> "jpeg"
                h.size >= 8 && String(h.copyOfRange(4, 8)) == "ftyp" -> "heic/mp4"
                h.size >= 4 && h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() -> "riff (webp/avi/wav)"
                h.size >= 8 && h[0] == 0x89.toByte() && h[1] == 0x50.toByte() -> "png"
                else -> "unknown(0x%02X%02X)".format(h[0], h[1])
            }
        } catch (_: Exception) { "unreadable" }
    }

    // Returns whether the copy actually succeeded — callers use this to decide whether the
    // combined output needs a fallback date stamp (see combineAll). Drains stdout/stderr
    // before waiting on the process, matching the guard already applied to every other
    // exiftool invocation in the codebase (the pipe otherwise fills and waitFor() blocks
    // forever on verbose output).
    private fun copyExif(sourcePath: String, destPath: String): Boolean {
        val exiftoolPath = BinaryExtractor.checkCommand("exiftool") ?: return false
        return try {
            val proc = ProcessBuilder(
                exiftoolPath, "-overwrite_original", "-q",
                "-TagsFromFile", sourcePath, "-all:all", destPath
            ).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().readText()
            proc.waitForOrKill() == 0
        } catch (e: InterruptedException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    // A combined output already has a real capture timestamp when copyExif (images) or the
    // TagsFromFile copy inside combineVideoWithOverlay (videos) propagated one from the
    // -main file's own metadata. Read it back by content rather than trusting the copy step's
    // exit code alone — the copy can "succeed" while copying zero useful tags (metadata phase
    // was disabled, or never matched a timestamp for this file), which must still fall back to
    // combineAll's date-only batch below.
    private fun hasDateTag(filePath: String, isVideo: Boolean): Boolean {
        val exiftoolPath = BinaryExtractor.checkCommand("exiftool") ?: return false
        val tag = if (isVideo) "-CreateDate" else "-DateTimeOriginal"
        return try {
            val proc = ProcessBuilder(exiftoolPath, "-s3", tag, filePath)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitForOrKill() == 0 && output.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun extractUuid(name: String): String? {
        val afterDate = name.substringAfter("_", "")
        if (afterDate.isEmpty()) return null
        return afterDate.substringBefore("-main").substringBefore("-overlay")
            .takeIf { it.isNotEmpty() }
    }
}
