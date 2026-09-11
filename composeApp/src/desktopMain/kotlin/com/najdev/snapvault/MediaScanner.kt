package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.najdev.snapvault.metadata.SupportedMediaExtensions
import com.najdev.snapvault.model.FileMeta
import com.najdev.snapvault.ui.LibraryItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.*
import java.awt.Image
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.util.concurrent.TimeUnit

actual fun scanMediaFiles(folderPath: String): List<LibraryItem> {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return emptyList()

    val index: Map<String, FileMeta> = runCatching {
        Json.decodeFromString<Map<String, FileMeta>>(
            File(folder, "vault_index.json").readText()
        )
    }.getOrDefault(emptyMap())

    val mediaExtensions = SupportedMediaExtensions.ALL
    val videoExtensions = SupportedMediaExtensions.VIDEO
    return (folder.listFiles() ?: return emptyList())
        .filter { it.isFile && it.extension.lowercase() in mediaExtensions }
        // Snapchat/SnapVault exports deliberately put the capture *date* in every file name.
        // A filesystem timestamp is only when the file was copied, restored, or extracted, so
        // it is not a reliable proxy for when the memory was captured. Keep it as a fallback
        // for user-supplied media that does not follow the export naming convention.
        //
        // Computed once per file (not inside the comparator) so a stat + regex/parse isn't
        // repeated on every comparison. The day-truncated key gives filename-dated and
        // mtime-fallback files a common scale — comparing raw millis would let an arbitrary
        // fallback file's full-precision timestamp always outrank a dated memory captured
        // earlier the same day. Within a shared day, a dated file still outranks a fallback
        // file outright (provenance beats a foreign mtime), and mtime only breaks ties among
        // files of the same provenance.
        .map { file ->
            val captureDate = captureDateFromFileName(file.name)
            val lastModifiedMillis = file.lastModified()
            val dayKeyMillis = captureDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
                ?: Math.floorDiv(lastModifiedMillis, MILLIS_PER_DAY) * MILLIS_PER_DAY
            ScannedFile(file, captureDate, dayKeyMillis, lastModifiedMillis)
        }
        .sortedWith(
            compareByDescending<ScannedFile> { it.dayKeyMillis }
                .thenByDescending { it.captureDate != null }
                .thenByDescending { it.lastModifiedMillis }
                .thenBy { it.file.name }
        )
        .map { scanned ->
            val meta = index[scanned.file.name]
            LibraryItem(
                id = scanned.file.absolutePath,
                date = scanned.captureDate?.let { formatCaptureDate(it) } ?: formatFileDate(scanned.lastModifiedMillis),
                title = scanned.file.nameWithoutExtension,
                type = if (scanned.file.extension.lowercase() in videoExtensions) "video" else "photo",
                hasGps = meta?.hasGps ?: false,
                hasOverlay = meta?.hasOverlay ?: false,
                fileSizeBytes = scanned.file.length()
            )
        }
}

private const val MILLIS_PER_DAY = 86_400_000L

private data class ScannedFile(
    val file: File,
    val captureDate: LocalDate?,
    val dayKeyMillis: Long,
    val lastModifiedMillis: Long,
)

actual fun loadThumbnail(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.exists()) return null
    val ext = file.extension.lowercase()
    val parent = file.parentFile ?: return null
    
    val cacheDir = File(parent, ".thumbnails")
    val thumbFile = File(cacheDir, "${file.name}.jpg")
    
    if (thumbFile.exists()) {
        return runCatching {
            SkiaImage.makeFromEncoded(thumbFile.readBytes()).toComposeImageBitmap()
        }.getOrNull()
    }
    
    if (!cacheDir.exists()) {
        cacheDir.mkdirs()
    }
    
    if (ext in setOf("mp4", "mov", "gif")) {
        val ffmpegPath = BinaryExtractor.checkCommand("ffmpeg") ?: return null
        try {
            val args = listOf(
                ffmpegPath,
                "-y",
                "-ss", "00:00:00.000",
                "-i", file.absolutePath,
                "-vframes", "1",
                "-vf", "scale=320:-1",
                thumbFile.absolutePath
            )
            val process = ProcessBuilder(args).start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    } else if (ext in setOf("jpg", "jpeg", "png")) {
        try {
            val original = ImageIO.read(file)
            if (original != null) {
                val targetSize = 320
                val width = original.width
                val height = original.height
                val (newWidth, newHeight) = if (width > height) {
                    targetSize to (height * targetSize / width)
                } else {
                    (width * targetSize / height) to targetSize
                }
                val scaled = original.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH)
                val buffered = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
                val g2d = buffered.createGraphics()
                g2d.drawImage(scaled, 0, 0, null)
                g2d.dispose()
                ImageIO.write(buffered, "jpg", thumbFile)
            } else {
                return null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return runCatching {
                SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
            }.getOrNull()
        }
    } else {
        return null
    }
    
    if (thumbFile.exists()) {
        return runCatching {
            SkiaImage.makeFromEncoded(thumbFile.readBytes()).toComposeImageBitmap()
        }.getOrNull()
    }
    return null
}

actual fun loadFullImage(path: String): ImageBitmap? {
    val file = File(path)
    if (!file.exists() || file.extension.lowercase() in setOf("mp4", "mov", "gif")) return null
    return runCatching {
        SkiaImage.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
    }.getOrNull()
}

// Both date paths follow the user's locale. They used to reach English by two different
// routes — this one pinned Locale.US outright, and formatCaptureDate below took the first
// three letters of a Java enum constant — so every date in the Library was English whatever
// the system language was.
private fun formatFileDate(millis: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat(DISPLAY_DATE_PATTERN, locale)
        .apply { timeZone = TimeZone.getDefault() }
        .format(Date(millis))
        .uppercase(locale)

private val SNAPVAULT_FILE_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})_""")

/** Returns the date encoded by SnapVault's `YYYY-MM-DD_<id>` output format, when valid. */
private fun captureDateFromFileName(name: String): LocalDate? {
    val match = SNAPVAULT_FILE_DATE.find(name) ?: return null
    val (year, month, day) = match.destructured
    return runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
}

private const val DISPLAY_DATE_PATTERN = "MMM dd, yyyy"

// Format the parsed calendar date directly. Converting midnight UTC to a local Date would
// make west-of-UTC users see the previous day — so this stays on LocalDate and only the
// month name comes from the locale.
private fun formatCaptureDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(DateTimeFormatter.ofPattern(DISPLAY_DATE_PATTERN, locale)).uppercase(locale)
