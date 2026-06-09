package com.najdev.snapvault.parser

data class CorrelatedMeta(
    val uuid: String,
    val fullDateTime: String,
    val latitude: Double?,
    val longitude: Double?
)

object MetadataCorrelator {

    /**
     * Primary path: positional alignment between memories/memories.html (UUID, oldest-first)
     * and html/memories_history.html (full datetime + GPS, newest-first). Within each
     * (date, mediaType) group, reversing the hist group gives oldest-first order that aligns
     * position-by-position with the memories.html group.
     *
     * Fallback: for any UUID not covered by hist alignment, fall back to JSON bucket-pop
     * keyed by (dateOnly, mediaType).
     */
    fun correlate(
        histEntries: List<HistMemoryEntry>,
        jsonEntries: List<JsonMemoryEntry>,
        htmlEntries: List<HtmlMemoryEntry>
    ): Map<String, CorrelatedMeta> {
        // Group hist entries by (date, mediaType) preserving newest-first file order
        val histByKey = LinkedHashMap<Pair<String, String>, MutableList<HistMemoryEntry>>()
        for (entry in histEntries) {
            histByKey.getOrPut(entry.date to entry.mediaType) { mutableListOf() }.add(entry)
        }

        // Group html entries by (date, mediaType) preserving oldest-first file order
        val htmlByKey = LinkedHashMap<Pair<String, String>, MutableList<HtmlMemoryEntry>>()
        for (entry in htmlEntries) {
            val mediaType = if (entry.isVideo) "Video" else "Image"
            htmlByKey.getOrPut(entry.date to mediaType) { mutableListOf() }.add(entry)
        }

        val result = mutableMapOf<String, CorrelatedMeta>()

        // Primary: positional alignment — reverse hist group (newest→oldest) and align with html (already oldest-first)
        for ((key, htmlGroup) in htmlByKey) {
            val histGroup = histByKey[key]?.reversed() ?: continue
            for ((i, htmlEntry) in htmlGroup.withIndex()) {
                val histEntry = histGroup.getOrNull(i) ?: break
                result[htmlEntry.uuid] = CorrelatedMeta(
                    uuid = htmlEntry.uuid,
                    fullDateTime = histEntry.fullDateTime,
                    latitude = histEntry.latitude,
                    longitude = histEntry.longitude
                )
            }
        }

        // Fallback: JSON bucket-pop for any UUID not covered by hist alignment
        val buckets = HashMap<Pair<String, String>, ArrayDeque<JsonMemoryEntry>>()
        for (entry in jsonEntries.reversed()) {
            val key = entry.dateOnly to entry.mediaType
            buckets.getOrPut(key) { ArrayDeque() }.addLast(entry)
        }
        for (htmlEntry in htmlEntries) {
            if (htmlEntry.uuid in result) continue
            val mediaType = if (htmlEntry.isVideo) "Video" else "Image"
            val jsonEntry = buckets[htmlEntry.date to mediaType]?.removeFirstOrNull() ?: continue
            result[htmlEntry.uuid] = CorrelatedMeta(
                uuid = htmlEntry.uuid,
                fullDateTime = jsonEntry.date,
                latitude = jsonEntry.latitude,
                longitude = jsonEntry.longitude
            )
        }

        return result
    }
}
