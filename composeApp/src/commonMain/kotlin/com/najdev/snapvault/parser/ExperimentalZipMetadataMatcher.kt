package com.najdev.snapvault.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

data class ZipMetadataTarget(
    val fileName: String,
    val dateStr: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val hasOverlay: Boolean = false,
)

data class ZipMetadataPlan(
    val targets: List<ZipMetadataTarget>,
    val warnings: List<String> = emptyList(),
)

enum class MediaKind { Image, Video }

// One "Saved Media" entry from memories_history.json, parsed for the real ZIP-export
// schema: Date + Media Type + Location. Unlike HistoryParser.parseJson (built for the
// legacy CDN-link export), this never requires a Download Link/Media Download Url —
// modern exports leave both empty, so requiring one silently discards every record.
data class ZipMemoryRecord(
    val dateStr: String,
    val epochSecond: Long,
    val mediaType: MediaKind?,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

private data class RecordKey(val epochSecond: Long, val mediaType: MediaKind)

private val fullDateTimeRegex = Regex("""(\d{4})-(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})""")
private val locationRegex = Regex(
    """Latitude,\s*Longitude:\s*([+-]?\d+\.?\d*),\s*([+-]?\d+\.?\d*)"""
)

// A file's ZIP extended-timestamp extra field and a memories_history.json record's
// "Date" field both encode the same capture instant to the second — that's the one
// real identifier Snapchat's export provides (see ZipReader.listZipEntryTimestamps).
// This parses memories_history.json into that same (mediaType, epochSecond) space so
// buildExperimentalZipMetadataPlan can match files to records by exact key instead of
// guessing a pairing within a same-day bucket.
fun parseZipMemoryRecords(jsonContent: String): List<ZipMemoryRecord> {
    return runCatching {
        val root = Json.parseToJsonElement(jsonContent).jsonObject
        val savedMedia = root["Saved Media"]?.jsonArray ?: return emptyList()
        savedMedia.mapNotNull { elem ->
            val obj = elem.jsonObject
            val dateStr = obj["Date"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val epochSecond = parseEpochSecondUtc(dateStr) ?: return@mapNotNull null
            val mediaType = when (obj["Media Type"]?.jsonPrimitive?.content) {
                "Image" -> MediaKind.Image
                "Video" -> MediaKind.Video
                else -> null
            }
            val (lat, lon) = parseLocation(obj["Location"]?.jsonPrimitive?.content)
            ZipMemoryRecord(
                dateStr = dateStr,
                epochSecond = epochSecond,
                mediaType = mediaType,
                latitude = lat,
                longitude = lon,
            )
        }
    }.getOrElse { emptyList() }
}

private fun parseLocation(locationStr: String?): Pair<Double?, Double?> {
    if (locationStr == null) return null to null
    val m = locationRegex.find(locationStr) ?: return null to null
    val lat = m.groupValues[1].toDoubleOrNull() ?: return null to null
    val lon = m.groupValues[2].toDoubleOrNull() ?: return null to null
    // Location services off / unavailable is reported as exactly (0, 0) — treat it the
    // same as "no location field" rather than writing null-island GPS onto files.
    if (lat == 0.0 && lon == 0.0) return null to null
    return lat to lon
}

fun buildExperimentalZipMetadataPlan(
    entries: Collection<HtmlMemoryEntry>,
    records: Collection<ZipMemoryRecord>,
): ZipMetadataPlan {
    if (entries.isEmpty()) {
        return ZipMetadataPlan(emptyList(), listOf("No ZIP media files were found."))
    }

    if (records.isEmpty()) {
        return ZipMetadataPlan(
            entries.flatMap { buildDateOnlyTargets(it) },
            listOf("No memories_history.json metadata was found; falling back to ZIP date-only metadata.")
        )
    }

    val recordsByKey: Map<RecordKey, List<ZipMemoryRecord>> = records
        .filter { it.mediaType != null }
        .groupBy { RecordKey(it.epochSecond, it.mediaType!!) }
    val consolidated: Map<RecordKey, ZipMemoryRecord> = recordsByKey.mapValues { (_, group) ->
        consolidateByLocation(group)
    }

    val targets = mutableListOf<ZipMetadataTarget>()
    var matchedCount = 0
    var ambiguousLocationCount = 0

    for (entry in entries) {
        val kind = if (entry.isVideo) MediaKind.Video else MediaKind.Image
        val key = entry.captureEpochSecond?.let { RecordKey(it, kind) }
        val record = key?.let { consolidated[it] }

        if (record == null) {
            targets += buildDateOnlyTargets(entry)
            continue
        }

        matchedCount++
        if (recordsByKey.getValue(key).size > 1 && record.latitude == null && record.longitude == null) {
            ambiguousLocationCount++
        }

        val target = ZipMetadataTarget(
            fileName = entry.fileName,
            dateStr = record.dateStr,
            latitude = record.latitude,
            longitude = record.longitude,
            hasOverlay = entry.hasOverlay,
        )
        targets += target
        if (entry.hasOverlay && entry.overlayFileName != null) {
            targets += target.copy(fileName = entry.overlayFileName, hasOverlay = false)
        }
    }

    val warnings = mutableListOf<String>()
    when {
        matchedCount == 0 -> warnings += "No files could be matched to memories_history.json by exact capture timestamp; falling back to ZIP date-only metadata for all files."
        matchedCount < entries.size -> warnings += "$matchedCount of ${entries.size} file(s) matched memories_history.json by exact capture timestamp; the rest fell back to ZIP date-only metadata."
    }
    if (ambiguousLocationCount > 0) {
        warnings += "$ambiguousLocationCount file(s) shared a capture timestamp with another memory that had a conflicting location; GPS was omitted for those to avoid tagging the wrong file."
    }

    return ZipMetadataPlan(targets, warnings)
}

// Multiple JSON records can share the same (media type, second) key — e.g. a burst of
// photos taken in the same second. There's still no identifier that says which record
// belongs to which of those files, so instead of guessing we keep the shared date
// (safe — it's the same instant for all of them) and only keep GPS when every record in
// the collision has a location and they all agree closely enough (<1km). If even one
// colliding record has no location, we can't tell whether *that* record is the one
// matching a given file, so no file in the group gets GPS.
private fun consolidateByLocation(group: List<ZipMemoryRecord>): ZipMemoryRecord {
    val base = group.first()
    if (group.size == 1) return base

    val located = group.filter { it.latitude != null && it.longitude != null }
    if (located.size != group.size) return base.copy(latitude = null, longitude = null)

    val anyFarApart = located.indices.any { i ->
        (i + 1 until located.size).any { j -> kilometersBetween(located[i], located[j]) >= 1.0 }
    }
    return if (anyFarApart) located.first().copy(latitude = null, longitude = null) else located.first()
}

private fun kilometersBetween(a: ZipMemoryRecord, b: ZipMemoryRecord): Double {
    val earthRadiusKm = 6371.0088
    val lat1 = a.latitude!!
    val lon1 = a.longitude!!
    val lat2 = b.latitude!!
    val lon2 = b.longitude!!
    val value = sin(degToRad(lat1)) * sin(degToRad(lat2)) +
        cos(degToRad(lat1)) * cos(degToRad(lat2)) * cos(degToRad(lon1 - lon2))
    return earthRadiusKm * acos(value.coerceIn(-1.0, 1.0))
}

private fun degToRad(degrees: Double): Double = degrees * kotlin.math.PI / 180.0

private fun buildDateOnlyTargets(entry: HtmlMemoryEntry): List<ZipMetadataTarget> {
    val target = ZipMetadataTarget(
        fileName = entry.fileName,
        dateStr = fallbackDateString(entry.date),
        hasOverlay = entry.hasOverlay,
    )
    return if (entry.hasOverlay && entry.overlayFileName != null) {
        listOf(
            target,
            target.copy(fileName = entry.overlayFileName, hasOverlay = false),
        )
    } else {
        listOf(target)
    }
}

private fun fallbackDateString(dateKey: String): String = "$dateKey 00:00:00 UTC"

private fun parseEpochSecondUtc(dateStr: String): Long? {
    val m = fullDateTimeRegex.find(dateStr) ?: return null
    // MatchResult.Destructured only supports up to component5(), and this pattern has
    // 6 groups, so index into groupValues directly instead of destructuring.
    val g = m.groupValues
    return daysFromCivil(g[1].toInt(), g[2].toInt(), g[3].toInt()) * 86400L +
        g[4].toInt() * 3600L + g[5].toInt() * 60L + g[6].toInt()
}

// Howard Hinnant's public-domain days-since-epoch algorithm for the proleptic Gregorian
// calendar (http://howardhinnant.github.io/date_algorithms.html#days_from_civil). Used
// instead of a datetime library so this stays dependency-free in commonMain.
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = (y - era * 400).toLong() // [0, 399]
    val mp = (month + 9) % 12 // [0, 11]
    val doy = (153 * mp + 2) / 5 + day - 1 // [0, 365]
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy // [0, 146096]
    return era * 146097L + doe - 719468L
}
