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
        onProgress: (CombineResult) -> Unit
    ) {
        val pairs = findPairs(outputDir)
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
            val ok = if (pair.isVideo) {
                mediaProcessor.combineVideoWithOverlay(
                    pair.mainFile.absolutePath,
                    pair.overlayFile.absolutePath,
                    pair.outputFile.absolutePath
                )
            } else {
                combineImages(pair.mainFile, pair.overlayFile, pair.outputFile)
            }

            if (!ok) return "error: combine failed"

            if (!pair.isVideo) {
                // For images, combineVideoWithOverlay isn't called so we copy EXIF manually
                copyExif(pair.mainFile.absolutePath, pair.outputFile.absolutePath)
            }
            // Note: combineVideoWithOverlay already handles EXIF copy for video internally

            if (deleteOriginals) {
                pair.mainFile.delete()
                pair.overlayFile.delete()
            }

            "combined"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun combineImages(mainFile: File, overlayFile: File, outputFile: File): Boolean {
        return try {
            val mainImg = ImageIO.read(mainFile) ?: return false
            val overlayImg = ImageIO.read(overlayFile) ?: return false

            val w = mainImg.width
            val h = mainImg.height

            val combined = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = combined.createGraphics()
            // Quality hints so bilinear scaling handles semi-transparent edges correctly
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            // Draw main at native size
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
            true
        } catch (e: Exception) {
            false
        }
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
