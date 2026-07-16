package com.najdev.snapvault.parser

import com.najdev.snapvault.model.MemoryItem

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

private enum class MediaKind { Image, Video }

private data class MemoryBucketKey(
    val dateKey: String,
    val mediaKind: MediaKind?,
)

private val isoDateTimeRegex = Regex(
    """(\d{4})-(\d{2})-(\d{2})(?:\s+(\d{2}):(\d{2}):(\d{2}))?"""
)

private val europeanDateTimeRegex = Regex(
    """(\d{2})\.(\d{2})\.(\d{4})(?:\s+(\d{2}):(\d{2}):(\d{2}))?"""
)

fun buildExperimentalZipMetadataPlan(
    entries: Collection<HtmlMemoryEntry>,
    memories: Collection<MemoryItem>,
): ZipMetadataPlan {
    if (entries.isEmpty()) {
        return ZipMetadataPlan(emptyList(), listOf("No ZIP media files were found."))
    }

    if (memories.isEmpty()) {
        val fallbackTargets = entries.flatMap { entry ->
            buildDateOnlyTargets(entry)
        }
        return ZipMetadataPlan(
            fallbackTargets,
            listOf("No memories_history.json metadata was found; falling back to ZIP date-only metadata.")
        )
    }

    val memoryBuckets = mutableMapOf<MemoryBucketKey, MutableList<MemoryItem>>()
    val dateOnlyBuckets = mutableMapOf<String, MutableList<MemoryItem>>()
    memories.forEach { memory ->
        val dateKey = normalizeDateKey(memory.dateStr) ?: return@forEach
        val key = MemoryBucketKey(dateKey, inferMediaKind(memory.url))
        if (key.mediaKind == null) {
            dateOnlyBuckets.getOrPut(dateKey) { mutableListOf() }.add(memory)
        } else {
            memoryBuckets.getOrPut(key) { mutableListOf() }.add(memory)
        }
    }

    val targets = mutableListOf<ZipMetadataTarget>()
    val warnings = mutableListOf<String>()

    val entryBuckets = entries.groupBy { MemoryBucketKey(it.date, if (it.isVideo) MediaKind.Video else MediaKind.Image) }
    for ((key, bucketEntries) in entryBuckets) {
        val sortedEntries = bucketEntries.sortedBy { it.fileName }
        val strictMatches = memoryBuckets[key].orEmpty().sortedWith(memorySortOrder)
        val relaxedMatches = dateOnlyBuckets[key.dateKey].orEmpty().sortedWith(memorySortOrder)
        val matchedMemories = (strictMatches + relaxedMatches)
            .distinctBy { it.id }
            .sortedWith(memorySortOrder)

        if (matchedMemories.isEmpty()) {
            warnings += "No JSON metadata matched ${key.dateKey} ${key.mediaKind?.name?.lowercase() ?: "media"}; using ZIP date-only metadata for those files."
            sortedEntries.forEach { entry ->
                targets += buildDateOnlyTargets(entry)
            }
            continue
        }

        if (matchedMemories.size != sortedEntries.size) {
            warnings += "Experimental metadata matching found ${sortedEntries.size} file(s) but ${matchedMemories.size} metadata entr${if (matchedMemories.size == 1) "y" else "ies"} for ${key.dateKey}; remaining files will use ZIP date-only metadata."
        }

        sortedEntries.forEachIndexed { index, entry ->
            val memory = matchedMemories.getOrNull(index)
            val target = if (memory != null) {
                ZipMetadataTarget(
                    fileName = entry.fileName,
                    dateStr = memory.dateStr ?: fallbackDateString(entry.date),
                    latitude = memory.latitude,
                    longitude = memory.longitude,
                    hasOverlay = entry.hasOverlay,
                )
            } else {
                ZipMetadataTarget(
                    fileName = entry.fileName,
                    dateStr = fallbackDateString(entry.date),
                    hasOverlay = entry.hasOverlay,
                )
            }

            targets += target
            if (entry.hasOverlay && entry.overlayFileName != null) {
                targets += target.copy(fileName = entry.overlayFileName, hasOverlay = false)
            }
        }
    }

    return ZipMetadataPlan(targets, warnings)
}

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

private val memorySortOrder = compareBy<MemoryItem>(
    { normalizeDateTimeKey(it.dateStr) ?: "" },
    { it.latitude == null || it.longitude == null },
    { it.url },
)

private fun inferMediaKind(url: String): MediaKind? {
    val extension = url.substringBefore('?').substringAfterLast('.', "").lowercase()
    return when (extension) {
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "tif", "tiff" -> MediaKind.Image
        "mp4", "mov", "avi", "mkv", "m4v" -> MediaKind.Video
        else -> null
    }
}

private fun normalizeDateKey(dateStr: String?): String? {
    val cleaned = dateStr?.trim() ?: return null
    isoDateTimeRegex.find(cleaned)?.let { match ->
        return "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}"
    }
    europeanDateTimeRegex.find(cleaned)?.let { match ->
        return "${match.groupValues[3]}-${match.groupValues[2]}-${match.groupValues[1]}"
    }
    return null
}

private fun normalizeDateTimeKey(dateStr: String?): String? {
    val cleaned = dateStr?.trim() ?: return null
    isoDateTimeRegex.find(cleaned)?.let { match ->
        return "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}${match.groupValues[4].ifEmpty { "00" }}${match.groupValues[5].ifEmpty { "00" }}${match.groupValues[6].ifEmpty { "00" }}"
    }
    europeanDateTimeRegex.find(cleaned)?.let { match ->
        return "${match.groupValues[3]}${match.groupValues[2]}${match.groupValues[1]}${match.groupValues[4].ifEmpty { "00" }}${match.groupValues[5].ifEmpty { "00" }}${match.groupValues[6].ifEmpty { "00" }}"
    }
    return null
}

private fun fallbackDateString(dateKey: String): String = "$dateKey 00:00:00 UTC"