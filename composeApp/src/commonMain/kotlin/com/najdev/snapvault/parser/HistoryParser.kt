package com.najdev.snapvault.parser

import com.fleeksoft.ksoup.Ksoup
import com.najdev.snapvault.model.MemoryItem
import okio.ByteString.Companion.encodeUtf8

object HistoryParser {
    private val downloadMemoriesRegex = Regex("""downloadMemories\('(.+?)',\s*this,\s*(true|false)\)""")
    private val locationRegex = Regex("""Latitude,\s*Longitude:\s*([+-]?\d+\.?\d*),\s*([+-]?\d+\.?\d*)""")

    fun parse(htmlContent: String): List<MemoryItem> {
        val matches = downloadMemoriesRegex.findAll(htmlContent).toList()
        
        val doc = Ksoup.parse(htmlContent)
        val rows = doc.select("body > div.rightpanel > table > tbody > tr")
        
        val dates = mutableListOf<String>()
        val locations = mutableListOf<Pair<Double, Double>?>()
        
        for (row in rows) {
            val cells = row.select("td")
            if (cells.isNotEmpty()) {
                val dateText = cells[0].text().trim()
                dates.add(dateText)
                
                var location: Pair<Double, Double>? = null
                for (cell in cells) {
                    val text = cell.text().trim()
                    val match = locationRegex.find(text)
                    if (match != null) {
                        val lat = match.groupValues[1].toDoubleOrNull()
                        val lon = match.groupValues[2].toDoubleOrNull()
                        if (lat != null && lon != null) {
                            location = Pair(lat, lon)
                        }
                        break
                    }
                }
                locations.add(location)
            }
        }
        
        val items = mutableListOf<MemoryItem>()
        for (i in matches.indices) {
            val match = matches[i]
            val url = match.groupValues[1]
            val isGet = match.groupValues[2] == "true"
            val id = extractUniqueId(url)
            
            val dateStr = if (i < dates.size) dates[i] else null
            val loc = if (i < locations.size) locations[i] else null
            
            items.add(
                MemoryItem(
                    id = id,
                    url = url,
                    isGet = isGet,
                    dateStr = dateStr,
                    latitude = loc?.first,
                    longitude = loc?.second
                )
            )
        }
        return items
    }

    fun extractUniqueId(url: String): String {
        val midMatch = Regex("""mid=([a-zA-Z0-9\-]+)""").find(url)
        return if (midMatch != null) {
            midMatch.groupValues[1]
        } else {
            url.encodeUtf8().md5().hex()
        }
    }
}
