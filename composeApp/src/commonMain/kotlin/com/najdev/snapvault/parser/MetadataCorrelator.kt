package com.najdev.snapvault.parser

enum class CorrSource { Hist, Json  }

//META result type: holds uuid→(fullDateTime with time, GPS) after correlating hist/json entries to HTML file entries
data class CorrelatedMeta(
    val uuid: String,
    val fullDateTime: String, //META full "YYYY-MM-DD HH:MM:SS UTC" timestamp — richer than the date-only from filename
    val latitude: Double?,
    val longitude: Double?,
    val source: CorrSource = CorrSource.Hist
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
    //META correlate(): matches each HTML entry (uuid, date-only) to a hist/json entry (fullDateTime + GPS)
    //META primary path: positional alignment within (date, mediaType) groups; fallback: JSON bucket-pop
    //META IMPORTANT: result is computed but NOT currently used in DashboardViewModel — GPS and fullDateTime are unused in ZIP pipeline
    fun correlate(
        histEntries: List<HistMemoryEntry>,
        jsonEntries: List<JsonMemoryEntry>,
        htmlEntries: List<HtmlMemoryEntry>
    ): Map<String, CorrelatedMeta> {
        //META group hist by (date, mediaType) newest-first (as it appears in history file)
        val histByKey = LinkedHashMap<Pair<String, String>, MutableList<HistMemoryEntry>>()
        for (entry in histEntries) {
            histByKey.getOrPut(entry.date to entry.mediaType) { mutableListOf() }.add(entry)
        }

        //META group html by (date, mediaType) oldest-first (as extracted from memories.html)
        val htmlByKey = LinkedHashMap<Pair<String, String>, MutableList<HtmlMemoryEntry>>()
        for (entry in htmlEntries) {
            val mediaType = if (entry.isVideo) "Video" else "Image"
            htmlByKey.getOrPut(entry.date to mediaType) { mutableListOf() }.add(entry)
        }

        val result = mutableMapOf<String, CorrelatedMeta>()

        //META primary alignment: reverse hist group to oldest-first, zip positionally with html group
        for ((key, htmlGroup) in htmlByKey) {
            val histGroup = histByKey[key]?.reversed() ?: continue
            for ((i, htmlEntry) in htmlGroup.withIndex()) {
                val histEntry = histGroup.getOrNull(i) ?: break
                // Guard: hist date must match the filename date. By construction this should
                // always hold (both sides are keyed by the same date), but cross-zip ordering
                // anomalies can occasionally produce a mismatch. Discard rather than write
                // the wrong date — the JSON fallback below will re-try with the correct key.
                if (histEntry.date != key.first) continue
                result[htmlEntry.uuid] = CorrelatedMeta(
                    uuid = htmlEntry.uuid,
                    fullDateTime = histEntry.fullDateTime, //META full timestamp including time-of-day from history_memories.html
                    latitude = histEntry.latitude,         //META GPS from history_memories.html
                    longitude = histEntry.longitude
                )
            }
        }

        //META JSON fallback: for any uuid not matched by hist, pop an entry from memories_history.json by (dateOnly, mediaType)
        //META JSON entries have date with time but no guaranteed time precision; GPS from JSON Location field
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
                fullDateTime = jsonEntry.date, //META full date from memories_history.json
                latitude = jsonEntry.latitude,
                longitude = jsonEntry.longitude,
                source = CorrSource.Json
            )
        }

        return result
    }
}
