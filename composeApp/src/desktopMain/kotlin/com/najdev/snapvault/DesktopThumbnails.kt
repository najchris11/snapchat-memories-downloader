package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.najdev.snapvault.metadata.SupportedMediaExtensions
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The Library's on-disk thumbnail cache, `.thumbnails/` inside the library folder.
 *
 * Everything here answers one question the old cache never asked: is this thumbnail still a
 * picture of that file (D19)?
 *
 * - **Versioned names.** A thumbnail is `<source name>.<size>-<mtime>.jpg`. Replacing a file
 *   under the same name — a re-export, an edit, a combine that rewrote it — changes the name
 *   the cache looks for, so the old picture is never served for the new file. Older versions
 *   and the unversioned names earlier builds wrote are removed when a new one is made.
 * - **Staged writes.** Generation writes a separate file and renames it into place only once
 *   it decodes. A timeout, a failed ffmpeg run or a crash leaves nothing under the final name.
 * - **Recovery.** A cached file that will not decode is deleted and regenerated rather than
 *   returned as a blank tile on every load.
 * - **Format parity.** Every format the Library lists gets an attempt: ImageIO for what it
 *   reads, ffmpeg for the rest, and a genuine null — "preview unavailable" — only when neither
 *   can.
 *
 * The ffmpeg seams are parameters so the timeout and failure paths can be exercised without
 * an ffmpeg installed.
 */
internal class DesktopThumbnails(
    private val ffmpegPath: () -> String? = { BinaryExtractor.checkCommand("ffmpeg") },
    private val runFfmpeg: (args: List<String>, timeoutMillis: Long) -> CommandResult = { args, timeout ->
        runCommand(args, timeout)
    },
) {

    fun load(path: String): ImageBitmap? {
        val source = File(path)
        if (!source.isFile) return null
        val ext = source.extension.lowercase()
        if (ext !in SupportedMediaExtensions.ALL) return null
        val cacheDir = File(source.parentFile ?: return null, CACHE_DIR_NAME)
        val thumbnail = File(cacheDir, versionedName(source))

        if (thumbnail.isFile) {
            decode(thumbnail)?.let { return it }
            // Unreadable: regenerate instead of serving a blank tile on every load from now on.
            thumbnail.delete()
        }

        cacheDir.mkdirs()
        evictOtherVersions(cacheDir, source, keep = thumbnail)

        // Unique per attempt: the grid card and the inspector can load the same file at once.
        val staged = File(cacheDir, "${thumbnail.nameWithoutExtension}$STAGED_MARKER${System.nanoTime()}.jpg")
        try {
            val generated = if (ext in IMAGEIO_FORMATS) {
                generateWithImageIO(source, staged) || generateWithFfmpeg(source, staged)
            } else {
                generateWithFfmpeg(source, staged)
            }
            if (!generated || !staged.isFile || staged.length() == 0L) return null
            val bitmap = decode(staged) ?: return null
            // Another load may have committed the same version first; theirs is as good as ours.
            if (!thumbnail.exists()) staged.renameTo(thumbnail)
            return bitmap
        } finally {
            // A no-op after a successful rename; clears the partial file on every other path.
            staged.delete()
        }
    }

    private fun decode(file: File): ImageBitmap? = runCatching {
        SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
    }.getOrNull()

    private fun generateWithImageIO(source: File, output: File): Boolean = runCatching {
        val original = ImageIO.read(source) ?: return false
        val (width, height) = if (original.width > original.height) {
            TARGET_SIZE to (original.height * TARGET_SIZE / original.width).coerceAtLeast(1)
        } else {
            (original.width * TARGET_SIZE / original.height).coerceAtLeast(1) to TARGET_SIZE
        }
        val scaled = original.getScaledInstance(width, height, Image.SCALE_SMOOTH)
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        buffered.createGraphics().apply {
            drawImage(scaled, 0, 0, null)
            dispose()
        }
        ImageIO.write(buffered, "jpg", output)
    }.getOrDefault(false)

    // One frame, scaled. The same arguments serve a video's first frame and a still image
    // ImageIO cannot read (HEIC, WebP, TIFF). The exit code is checked: a failed run that
    // wrote part of a frame used to be indistinguishable from a successful one.
    private fun generateWithFfmpeg(source: File, output: File): Boolean {
        val ffmpeg = ffmpegPath() ?: return false
        val args = listOf(
            ffmpeg, "-y",
            "-ss", "00:00:00.000",
            "-i", source.absolutePath,
            "-vframes", "1",
            "-vf", "scale=$TARGET_SIZE:-1",
            output.absolutePath,
        )
        return try {
            runFfmpeg(args, FFMPEG_TIMEOUT_MS).exitCode == 0
        } catch (_: CommandTimeoutException) {
            false
        }
    }

    // Older versions of this source's thumbnail, and the unversioned name earlier builds used.
    // Staged files for the *current* version are left alone — they may belong to a load still
    // in progress on another thread.
    private fun evictOtherVersions(cacheDir: File, source: File, keep: File) {
        val prefix = Regex.escape(source.name)
        val versioned = Regex("""^$prefix\.\d+-\d+(${Regex.escape(STAGED_MARKER)}\d+)?\.jpg$""")
        val current = keep.nameWithoutExtension
        cacheDir.listFiles()?.forEach { file ->
            val name = file.name
            val stale = name == "${source.name}.jpg" ||
                (versioned.matches(name) && name != keep.name && !name.startsWith(current + STAGED_MARKER))
            if (stale) file.delete()
        }
    }

    internal companion object {
        const val CACHE_DIR_NAME = ".thumbnails"
        private const val STAGED_MARKER = ".staged-"
        private const val TARGET_SIZE = 320
        private const val FFMPEG_TIMEOUT_MS = 10_000L
        private val IMAGEIO_FORMATS = setOf("jpg", "jpeg", "png")

        fun versionedName(source: File): String = "${source.name}.${source.length()}-${source.lastModified()}.jpg"
    }
}
