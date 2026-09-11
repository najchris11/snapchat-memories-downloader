package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.najdev.snapvault.model.FileMeta
import com.najdev.snapvault.ui.LibraryItem
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Image as SkiaImage
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter

private val mediaExtensions = setOf("jpg", "jpeg", "png", "mp4", "mov", "gif")
private val videoExtensions = setOf("mp4", "mov")
private val fs = FileSystem.SYSTEM
private const val THUMBNAIL_TARGET_SIZE = 320

actual fun scanMediaFiles(folderPath: String): List<LibraryItem> {
    val folder = folderPath.toPath()
    if (!fs.exists(folder)) return emptyList()

    val index: Map<String, FileMeta> = runCatching {
        Json.decodeFromString<Map<String, FileMeta>>(fs.read(folder / "vault_index.json") { readUtf8() })
    }.getOrDefault(emptyMap())

    return fs.list(folder)
        .filter { path ->
            val metadata = fs.metadata(path)
            metadata.isRegularFile && path.name.substringAfterLast('.', "").lowercase() in mediaExtensions
        }
        // Computed once per file (not inside the comparator) so a stat + regex/parse isn't
        // repeated on every comparison. The day-truncated key gives filename-dated and
        // mtime-fallback files a common scale — comparing raw millis would let an arbitrary
        // fallback file's full-precision timestamp always outrank a dated memory captured
        // earlier the same day. Within a shared day, a dated file still outranks a fallback
        // file outright (provenance beats a foreign mtime), and mtime only breaks ties among
        // files of the same provenance.
        .map { path ->
            val metadata = fs.metadata(path)
            val captureDate = captureDateFromFileName(path.name)
            val lastModifiedMillis = metadata.lastModifiedAtMillis ?: 0L
            val dayKeyMillis = captureDate?.sortableKey ?: lastModifiedMillis.floorDiv(MILLIS_PER_DAY) * MILLIS_PER_DAY
            ScannedPath(path, captureDate, dayKeyMillis, lastModifiedMillis, metadata.size ?: 0L)
        }
        .sortedWith(
            compareByDescending<ScannedPath> { it.dayKeyMillis }
                .thenByDescending { it.captureDate != null }
                .thenByDescending { it.lastModifiedMillis }
                .thenBy { it.path.name }
        )
        .map { scanned ->
            val meta = index[scanned.path.name]
            val ext = scanned.path.name.substringAfterLast('.', "").lowercase()
            LibraryItem(
                id = scanned.path.toString(),
                date = scanned.captureDate?.let { formatCaptureDate(it) } ?: formatFileDate(scanned.lastModifiedMillis),
                title = scanned.path.name.substringBeforeLast('.'),
                type = if (ext in videoExtensions) "video" else "photo",
                hasGps = meta?.hasGps ?: false,
                hasOverlay = meta?.hasOverlay ?: false,
                fileSizeBytes = scanned.size
            )
        }
}

private data class ScannedPath(
    val path: okio.Path,
    val captureDate: CaptureDate?,
    val dayKeyMillis: Long,
    val lastModifiedMillis: Long,
    val size: Long,
)

// Video/GIF thumbnails aren't implemented on iOS yet (would need AVAssetImageGenerator);
// ImageIO-backed decoding below only covers still images.
actual fun loadThumbnail(path: String): ImageBitmap? {
    val ext = path.substringAfterLast('.', "").lowercase()
    if (ext in videoExtensions || ext == "gif") return null
    val bytes = readBytesOrNull(path) ?: return null
    return runCatching {
        downsample(SkiaImage.makeFromEncoded(bytes), THUMBNAIL_TARGET_SIZE).toComposeImageBitmap()
    }.getOrNull()
}

actual fun loadFullImage(path: String): ImageBitmap? {
    val ext = path.substringAfterLast('.', "").lowercase()
    if (ext in videoExtensions || ext == "gif") return null
    val bytes = readBytesOrNull(path) ?: return null
    return runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}

private fun readBytesOrNull(path: String): ByteArray? {
    val p = path.toPath()
    if (!fs.exists(p)) return null
    return runCatching { fs.read(p) { readByteArray() } }.getOrNull()
}

private fun downsample(image: SkiaImage, targetSize: Int): SkiaImage {
    val width = image.width
    val height = image.height
    if (width <= targetSize && height <= targetSize) return image

    val (newWidth, newHeight) = if (width > height) {
        targetSize to (height * targetSize / width).coerceAtLeast(1)
    } else {
        (width * targetSize / height).coerceAtLeast(1) to targetSize
    }

    val bitmap = Bitmap().apply { allocN32Pixels(newWidth, newHeight) }
    Canvas(bitmap).drawImageRect(
        image,
        Rect.makeWH(width.toFloat(), height.toFloat()),
        Rect.makeWH(newWidth.toFloat(), newHeight.toFloat())
    )
    return SkiaImage.makeFromBitmap(bitmap)
}

// NSDate only exposes a reference-date constructor here, so shift from Unix epoch (1970-01-01)
// to NSDate's reference date (2001-01-01); the formatter defaults to the local time zone.
private const val REFERENCE_DATE_OFFSET_SECONDS = 978307200.0

private fun formatFileDate(millis: Long?): String {
    if (millis == null) return ""
    val date = NSDate(timeIntervalSinceReferenceDate = millis / 1000.0 - REFERENCE_DATE_OFFSET_SECONDS)
    // No explicit locale: NSDateFormatter defaults to the user's, which is what a displayed
    // date wants. en_US_POSIX was pinned here, which is the right choice for a machine-read
    // format and the wrong one for text on screen.
    val formatter = NSDateFormatter().apply { dateFormat = DISPLAY_DATE_PATTERN }
    return formatter.stringFromDate(date).uppercase()
}

private data class CaptureDate(val year: Int, val month: Int, val day: Int) {
    val sortableKey: Long get() = daysFromCivil(year, month, day) * MILLIS_PER_DAY
}

private val SNAPVAULT_FILE_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})_""")
private const val MILLIS_PER_DAY = 86_400_000L

private fun captureDateFromFileName(name: String): CaptureDate? {
    val match = SNAPVAULT_FILE_DATE.find(name) ?: return null
    val (yearText, monthText, dayText) = match.destructured
    val year = yearText.toIntOrNull() ?: return null
    val month = monthText.toIntOrNull() ?: return null
    val day = dayText.toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
    return CaptureDate(year, month, day)
}

private const val DISPLAY_DATE_PATTERN = "MMM dd, yyyy"

// The month name comes from the locale's own abbreviations rather than a hardcoded English
// list. Taken as a symbol rather than by formatting an NSDate on purpose: this is a plain
// calendar date, and turning it into an instant is the UTC-midnight bug that would show
// west-of-UTC users the previous day.
private fun formatCaptureDate(date: CaptureDate): String {
    val symbols = NSDateFormatter().shortMonthSymbols
    val month = (symbols.getOrNull(date.month - 1) as? String)?.uppercase()
        ?: date.month.toString().padStart(2, '0')
    return "$month ${date.day.toString().padStart(2, '0')}, ${date.year}"
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

// Days since 1970-01-01, using the proleptic Gregorian calendar. This keeps filename
// dates and filesystem millisecond timestamps on the same scale for mixed folders.
private fun daysFromCivil(inputYear: Int, month: Int, day: Int): Long {
    var year = inputYear
    year -= if (month <= 2) 1 else 0
    val era = if (year >= 0) year / 400 else (year - 399) / 400
    val yearOfEra = year - era * 400
    val dayOfYear = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146097L + dayOfEra.toLong() - 719468L
}
