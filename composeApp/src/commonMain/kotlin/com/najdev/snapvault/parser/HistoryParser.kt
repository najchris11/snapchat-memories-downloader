package com.najdev.snapvault.parser

import com.fleeksoft.ksoup.Ksoup
import com.najdev.snapvault.model.MemoryItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.encodeUtf8

object HistoryParser {
    // Applied to decoded onclick attribute values (no HTML entities)
    private val downloadMemoriesRegex = Regex(
        """downloadMemories\(['"]([^'"]+)['"]\s*,\s*[^,)]+,\s*(true|false)\s*\)"""
    )
    // Fallback: 2-arg form with no explicit isGet — assume POST
    private val downloadMemoriesTwoArgRegex = Regex(
        """downloadMemories\(['"]([^'"]+)['"]\s*,\s*[^)]+\)"""
    )
    private val locationRegex = Regex(
        """Latitude,\s*Longitude:\s*([+-]?\d+\.?\d*),\s*([+-]?\d+\.?\d*)"""
    )

    // Snapchat writes "0.0, 0.0" as a sentinel for "no location recorded", not a real
    // Null Island coordinate — treat it the same as a missing location field.
    private fun nullIfZero(lat: Double?, lon: Double?): Pair<Double?, Double?> =
        if (lat == 0.0 && lon == 0.0) null to null else lat to lon

    //META legacy HTML parse: extracts download URLs, dateStr ("YYYY-MM-DD HH:MM:SS UTC"), and GPS from memories_history.html
    //META GPS extracted from table row cells matching "Latitude, Longitude: X, Y" pattern
    fun parse(htmlContent: String): List<MemoryItem> {
        val doc = Ksoup.parse(htmlContent)

        // Use DOM to get decoded onclick values — handles &#39; / &quot; entity encoding
        val onclickEls = doc.select("[onclick*=downloadMemories]")

        if (onclickEls.isNotEmpty()) {
            return onclickEls.mapNotNull { el ->
                val onclick = el.attr("onclick")
                val m = downloadMemoriesRegex.find(onclick)
                    ?: downloadMemoriesTwoArgRegex.find(onclick)
                    ?: return@mapNotNull null

                val url = m.groupValues[1]
                if (!url.startsWith("http")) return@mapNotNull null
                val isGet = m.groupValues.getOrElse(2) { "false" } == "true"

                // Date and GPS come from the same row as the button
                val row = el.closest("tr")
                val cells = row?.select("td") ?: return@mapNotNull MemoryItem(
                    id = extractUniqueId(url), url = url, isGet = isGet,
                    dateStr = null, latitude = null, longitude = null
                )

                val dateStr = cells.firstOrNull()?.text()?.trim()

                var lat: Double? = null
                var lon: Double? = null
                for (cell in cells) {
                    val locMatch = locationRegex.find(cell.text())
                    if (locMatch != null) {
                        lat = locMatch.groupValues[1].toDoubleOrNull()
                        lon = locMatch.groupValues[2].toDoubleOrNull()
                        break
                    }
                }
                val (safeLat, safeLon) = nullIfZero(lat, lon)

                MemoryItem(
                    id = extractUniqueId(url),
                    url = url,
                    isGet = isGet,
                    dateStr = dateStr,
                    latitude = safeLat,
                    longitude = safeLon
                )
            }
        }

        // Fallback: raw text regex (for unencoded HTML)
        val rawMatches = downloadMemoriesRegex.findAll(htmlContent).toList()
            .ifEmpty { downloadMemoriesTwoArgRegex.findAll(htmlContent)
                .filter { it.groupValues[1].startsWith("http") }.toList() }

        val rows = doc.select("body > div.rightpanel > table > tbody > tr")
        val dates = rows.mapNotNull { it.select("td").firstOrNull()?.text()?.trim() }
        val locations = rows.map { row ->
            row.select("td").firstNotNullOfOrNull { cell ->
                locationRegex.find(cell.text())?.let {
                    it.groupValues[1].toDoubleOrNull()?.let { lat ->
                        it.groupValues[2].toDoubleOrNull()?.let { lon ->
                            val (safeLat, safeLon) = nullIfZero(lat, lon)
                            if (safeLat != null && safeLon != null) safeLat to safeLon else null
                        }
                    }
                }
            }
        }

        return rawMatches.mapIndexed { i, m ->
            val url = m.groupValues[1]
            val isGet = m.groupValues.getOrElse(2) { "false" } == "true"
            MemoryItem(
                id = extractUniqueId(url),
                url = url,
                isGet = isGet,
                dateStr = dates.getOrNull(i),
                latitude = locations.getOrNull(i)?.first,
                longitude = locations.getOrNull(i)?.second
            )
        }
    }

    //META legacy JSON parse: extracts download URLs, dateStr, and GPS from memories_history.json ("Saved Media" array)
    //META 0.0,0.0 (Snapchat's "no location" sentinel) is nulled out via nullIfZero, same as the HTML parse path
    fun parseJson(jsonContent: String): List<MemoryItem> {
        return runCatching {
            val root = Json.parseToJsonElement(jsonContent).jsonObject
            val savedMedia = root["Saved Media"]?.jsonArray ?: return emptyList()
            savedMedia.mapNotNull { elem ->
                val obj = elem.jsonObject
                val url = obj["Download Link"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (!url.startsWith("http")) return@mapNotNull null
                val dateStr = obj["Date"]?.jsonPrimitive?.content
                val locationStr = obj["Location"]?.jsonPrimitive?.content
                var lat: Double? = null
                var lon: Double? = null
                if (locationStr != null) {
                    val locMatch = locationRegex.find(locationStr)
                    if (locMatch != null) {
                        lat = locMatch.groupValues[1].toDoubleOrNull()
                        lon = locMatch.groupValues[2].toDoubleOrNull()
                    }
                }
                val (safeLat, safeLon) = nullIfZero(lat, lon)
                MemoryItem(
                    id = extractUniqueId(url),
                    url = url,
                    isGet = false,
                    dateStr = dateStr,
                    latitude = safeLat,
                    longitude = safeLon
                )
            }
        }.getOrElse { e ->
            throw IllegalArgumentException("Failed to parse memories_history.json: ${e.message}", e)
        }
    }

    fun diagnose(htmlContent: String): String {
        val naCount = htmlContent.split("""color: #999;">N/A""").size - 1
        if (naCount > 0 && "downloadMemories" in htmlContent) {
            return "All $naCount download links have expired (N/A). " +
                "Request a fresh data export from mydata.snapchat.com and use it within 7 days."
        }
        val doc = Ksoup.parse(htmlContent)
        val onclickEls = doc.select("[onclick*=downloadMemories]")
        val hasFn = "downloadMemories" in htmlContent
        val idx = if (hasFn) htmlContent.indexOf("downloadMemories") else -1
        val snippet = if (idx >= 0)
            htmlContent.substring(idx, minOf(htmlContent.length, idx + 200))
                .replace("\n", " ").replace("\r", "")
        else ""
        return "found=$hasFn; onclick elements=${onclickEls.size}; " +
            "primary regex=${downloadMemoriesRegex.findAll(htmlContent).count()}; " +
            "first onclick decoded=${onclickEls.firstOrNull()?.attr("onclick")?.take(150) ?: "n/a"}; " +
            "raw snippet: $snippet"
    }

    fun extractUniqueId(url: String): String {
        val midMatch = Regex("""mid=([a-zA-Z0-9\-]+)""").find(url)
        return if (midMatch != null) midMatch.groupValues[1]
        else url.encodeUtf8().md5().hex()
    }
}
