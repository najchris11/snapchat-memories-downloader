package com.najdev.snapvault.downloader

import com.najdev.snapvault.BinaryExtractor
import com.najdev.snapvault.metadata.MediaProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.image.BufferedImage  // needed for the combined RGB output
import java.io.File
import javax.imageio.ImageIO

class OverlayCombiner(private val mediaProcessor: MediaProcessor) {

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

        val mainFiles = allFiles.filter { "-main." in it.name }
        val overlayByUuid = allFiles
            .filter { "-overlay." in it.name }
            .mapNotNull { f -> extractUuid(f.name)?.let { it to f } }
            .toMap()

        return mainFiles.mapNotNull { mainFile ->
            val uuid = extractUuid(mainFile.name) ?: return@mapNotNull null
            val overlayFile = overlayByUuid[uuid] ?: return@mapNotNull null
            val ext = mainFile.extension
            // Output: drop -main suffix, keep date_uuid.ext
            val outputName = mainFile.name.replace("-main.$ext", ".$ext")
            OverlayPair(
                mainFile = mainFile,
                overlayFile = overlayFile,
                outputFile = File(outputDir, outputName),
                isVideo = ext.lowercase() in listOf("mp4", "mov", "avi", "mkv")
            )
        }
    }

    suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit = {},
        onProgress: (CombineResult) -> Unit
    ) {
        val pairs = findPairs(outputDir)
        onStart(pairs.size)
        val channel = Channel<CombineResult>(Channel.UNLIMITED)

        coroutineScope {
            launch {
                for (result in channel) onProgress(result)
            }

            pairs.chunked(workerCount).forEach { chunk ->
                chunk.map { pair ->
                    async(Dispatchers.IO) {
                        val uuid = extractUuid(pair.mainFile.name) ?: pair.mainFile.nameWithoutExtension
                        val result = processPair(pair, deleteOriginals)
                        channel.send(CombineResult(uuid, pair.outputFile.absolutePath, result))
                    }
                }.awaitAll()
            }

            channel.close()
        }
    }

    private fun processPair(pair: OverlayPair, deleteOriginals: Boolean): String {
        return try {
            val err = if (pair.isVideo) {
                if (mediaProcessor.combineVideoWithOverlay(
                        pair.mainFile.absolutePath,
                        pair.overlayFile.absolutePath,
                        pair.outputFile.absolutePath
                    )
                ) null else "video combine failed"
            } else {
                combineImages(pair.mainFile, pair.overlayFile, pair.outputFile)
            }

            if (err != null) {
                // Treat unreadable HEIC/WebP (by extension or magic bytes) as skipped, not error
                return if (isUnsupportedFormat(pair.mainFile) || isUnsupportedFormat(pair.overlayFile)) {
                    "skipped: unsupported-format"
                } else {
                    "error: $err"
                }
            }

            if (!pair.isVideo) {
                copyExif(pair.mainFile.absolutePath, pair.outputFile.absolutePath)
            }

            if (deleteOriginals) {
                pair.mainFile.delete()
                pair.overlayFile.delete()
            }

            "combined"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    // Returns null on success or a human-readable reason string on failure.
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
            // Quality hints so bilinear scaling handles semi-transparent edges correctly
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            g.drawImage(mainImg, 0, 0, null)

            // Scale overlay to match main in one step — no intermediate ARGB buffer, which
            // would otherwise interpolate in non-premultiplied space and produce dark fringes
            // on transparent edges when the overlay dimensions differ from the main.
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER)
            g.drawImage(overlayImg, 0, 0, w, h, null)

            g.dispose()

            val ext = outputFile.extension.lowercase()
            val format = if (ext == "png") "PNG" else "JPEG"
            ImageIO.write(combined, format, outputFile)
            null
        } catch (e: Exception) {
            e.message ?: "unknown exception"
        }
    }

    // Checks extension and reads magic bytes to detect formats ImageIO cannot decode.
    private fun isUnsupportedFormat(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in setOf("heic", "heif", "webp")) return true
        return try {
            val header = file.inputStream().use { it.readNBytes(12) }
            val isHeic = header.size >= 8 && String(header.copyOfRange(4, 8)) == "ftyp"
            val isWebp = header.size >= 4 &&
                header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()
            isHeic || isWebp
        } catch (_: Exception) { false }
    }

    // Returns a short human-readable format name from magic bytes.
    private fun detectFormat(file: File): String {
        return try {
            val h = file.inputStream().use { it.readNBytes(12) }
            when {
                h.isEmpty() -> "empty"
                h.size >= 2 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() -> "jpeg"
                h.size >= 8 && String(h.copyOfRange(4, 8)) == "ftyp" -> "heic/mp4"
                h.size >= 4 && h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() -> "webp"
                h.size >= 8 && h[0] == 0x89.toByte() && h[1] == 0x50.toByte() -> "png"
                else -> "unknown(0x%02X%02X)".format(h[0], h[1])
            }
        } catch (_: Exception) { "unreadable" }
    }

    private fun copyExif(sourcePath: String, destPath: String) {
        val exiftoolPath = BinaryExtractor.checkCommand("exiftool") ?: return
        try {
            ProcessBuilder(
                exiftoolPath,
                "-overwrite_original",
                "-q",
                "-TagsFromFile", sourcePath,
                "-all:all",
                destPath
            ).start().waitFor()
        } catch (_: Exception) {}
    }

    private fun extractUuid(name: String): String? {
        val afterDate = name.substringAfter("_", "")
        if (afterDate.isEmpty()) return null
        return afterDate.substringBefore("-main").substringBefore("-overlay")
            .takeIf { it.isNotEmpty() }
    }
}
