package com.najdev.snapvault

import com.najdev.snapvault.metadata.SupportedMediaExtensions
import com.najdev.snapvault.model.FileMeta
import com.najdev.snapvault.ui.LibraryItem
import okio.FileSystem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Shared JVM/Android directory scanner for the Library screen (#52).
 *
 * Avoids `java.time.*` so the implementation stays compatible with Android `minSdk = 24`
 * without requiring core library desugaring, while preserving the UTC calendar-date
 * invariant (a `YYYY-MM-DD_` prefix must never shift backward by one day for users west of
 * UTC) and dynamic `Locale.getDefault()` month formatting.
 */
fun scanJvmMediaFiles(folderPath: String): List<LibraryItem> {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return emptyList()

    val index: Map<String, FileMeta> = VaultIndex.read(FileSystem.SYSTEM, folderPath)
    val mediaExtensions = SupportedMediaExtensions.ALL
    val videoExtensions = SupportedMediaExtensions.VIDEO

    return (folder.listFiles() ?: return emptyList())
        .filter { it.isFile && it.extension.lowercase() in mediaExtensions }
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
            val dayKeyMillis = captureDate?.sortableDayMillis
                ?: floorDivMillis(lastModifiedMillis, MILLIS_PER_DAY) * MILLIS_PER_DAY
            ScannedJvmFile(file, captureDate, dayKeyMillis, lastModifiedMillis)
        }
        .sortedWith(
            compareByDescending<ScannedJvmFile> { it.dayKeyMillis }
                .thenByDescending { it.captureDate != null }
                .thenByDescending { it.lastModifiedMillis }
                .thenBy { it.file.name }
        )
        .map { scanned ->
            val meta = index[scanned.file.name]
            LibraryItem(
                id = scanned.file.absolutePath,
                date = scanned.captureDate?.let { formatCaptureDate(it) }
                    ?: formatFileDate(scanned.lastModifiedMillis),
                title = scanned.file.nameWithoutExtension,
                type = if (scanned.file.extension.lowercase() in videoExtensions) "video" else "photo",
                hasGps = meta?.hasGps ?: false,
                latitude = meta?.latitude,
                longitude = meta?.longitude,
                hasOverlay = meta?.combined ?: false,
                favorited = meta?.favorited ?: false,
                fileSizeBytes = scanned.file.length(),
            )
        }
}

private const val MILLIS_PER_DAY = 86_400_000L
private const val DISPLAY_DATE_PATTERN = "MMM dd, yyyy"
private const val NOON_UTC_HOUR = 12
private val UTC_ZONE: TimeZone = TimeZone.getTimeZone("UTC")
private val SNAPVAULT_FILE_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})_""")

private data class JvmCaptureDate(val year: Int, val month: Int, val day: Int) {
    val sortableDayMillis: Long
        get() = daysFromCivil(year, month, day) * MILLIS_PER_DAY
}

private data class ScannedJvmFile(
    val file: File,
    val captureDate: JvmCaptureDate?,
    val dayKeyMillis: Long,
    val lastModifiedMillis: Long,
)

private fun floorDivMillis(x: Long, y: Long): Long {
    var r = x / y
    if ((x xor y) < 0 && r * y != x) r--
    return r
}

private fun captureDateFromFileName(name: String): JvmCaptureDate? {
    val match = SNAPVAULT_FILE_DATE.find(name) ?: return null
    val (yearText, monthText, dayText) = match.destructured
    val year = yearText.toIntOrNull() ?: return null
    val month = monthText.toIntOrNull() ?: return null
    val day = dayText.toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
    return JvmCaptureDate(year, month, day)
}

private fun formatFileDate(millis: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat(DISPLAY_DATE_PATTERN, locale)
        .apply { timeZone = TimeZone.getDefault() }
        .format(Date(millis))
        .uppercase(locale)

// Both GregorianCalendar and SimpleDateFormat are pinned to UTC at noon so a plain calendar
// date (`YYYY-MM-DD`) never rolls backward into the prior day in Americas time zones, while
// month abbreviations still follow `locale`.
private fun formatCaptureDate(date: JvmCaptureDate, locale: Locale = Locale.getDefault()): String {
    val cal = GregorianCalendar(UTC_ZONE, locale).apply {
        clear()
        set(Calendar.YEAR, date.year)
        set(Calendar.MONTH, date.month - 1)
        set(Calendar.DAY_OF_MONTH, date.day)
        set(Calendar.HOUR_OF_DAY, NOON_UTC_HOUR)
    }
    return SimpleDateFormat(DISPLAY_DATE_PATTERN, locale)
        .apply { timeZone = UTC_ZONE }
        .format(cal.time)
        .uppercase(locale)
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

private fun daysFromCivil(inputYear: Int, month: Int, day: Int): Long {
    var year = inputYear
    year -= if (month <= 2) 1 else 0
    val era = if (year >= 0) year / 400 else (year - 399) / 400
    val yearOfEra = year - era * 400
    val dayOfYear = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146097L + dayOfEra.toLong() - 719468L
}
