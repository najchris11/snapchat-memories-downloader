package com.najdev.snapvault.parser

data class CorrelatedMeta(
    val uuid: String,
    val fullDateTime: String,
    val latitude: Double?,
    val longitude: Double?
)

object MetadataCorrelator {

    /**
     * Correlates JSON metadata (newest-first) with HTML memory entries (oldest-first, aggregated
     * across all zips in chronological order). Matches by position, validated by date and media
     * type. Returns only confirmed matches keyed by UUID.
     *
     * The ~129-entry count difference between JSON and HTML comes from story saves and other
     * non-media entries in the JSON; unmatched JSON entries are skipped safely.
     */
    fun correlate(
        jsonEntries: List<JsonMemoryEntry>,
        htmlEntries: List<HtmlMemoryEntry>
    ): Map<String, CorrelatedMeta> {
        val result = mutableMapOf<String, CorrelatedMeta>()
        val reversedJson = jsonEntries.reversed()

        var jsonIndex = 0
        for (htmlEntry in htmlEntries) {
            if (jsonIndex >= reversedJson.size) break

            // Try to match at current jsonIndex, allowing up to 5 skips to absorb drift
            var matched = false
            for (skip in 0..5) {
                val ji = jsonIndex + skip
                if (ji >= reversedJson.size) break

                val jsonEntry = reversedJson[ji]
                if (datesMatch(jsonEntry.dateOnly, htmlEntry.date) &&
                    typesMatch(jsonEntry.mediaType, htmlEntry.isVideo)
                ) {
                    result[htmlEntry.uuid] = CorrelatedMeta(
                        uuid = htmlEntry.uuid,
                        fullDateTime = jsonEntry.date,
                        latitude = jsonEntry.latitude,
                        longitude = jsonEntry.longitude
                    )
                    jsonIndex = ji + 1
                    matched = true
                    break
                }
            }

            if (!matched) {
                // No match found within the skip window — advance JSON by 1 to re-sync
                jsonIndex++
            }
        }

        return result
    }

    private fun datesMatch(jsonDateOnly: String, htmlDate: String): Boolean =
        jsonDateOnly == htmlDate

    private fun typesMatch(mediaType: String, isVideo: Boolean): Boolean =
        if (isVideo) mediaType == "Video" else mediaType == "Image"
}
