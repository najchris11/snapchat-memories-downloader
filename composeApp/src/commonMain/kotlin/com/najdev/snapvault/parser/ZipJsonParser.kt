package com.najdev.snapvault.parser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class JsonMemoryEntry(
    val date: String,
    val dateOnly: String,
    val mediaType: String,
    val latitude: Double?,
    val longitude: Double?
)

object ZipJsonParser {

    private val locationRegex = Regex(
        """Latitude,\s*Longitude:\s*([+-]?\d+\.?\d*),\s*([+-]?\d+\.?\d*)"""
    )

    fun parse(jsonContent: String): List<JsonMemoryEntry> {
        return runCatching {
            val root = Json.parseToJsonElement(jsonContent).jsonObject
            val savedMedia = root["Saved Media"]?.jsonArray ?: return emptyList()
            savedMedia.mapNotNull { elem ->
                val obj = elem.jsonObject
                val date = obj["Date"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val dateOnly = date.take(10)
                val mediaType = obj["Media Type"]?.jsonPrimitive?.content ?: "Image"
                val locationStr = obj["Location"]?.jsonPrimitive?.content

                var lat: Double? = null
                var lon: Double? = null
                if (locationStr != null) {
                    val locMatch = locationRegex.find(locationStr)
                    if (locMatch != null) {
                        val parsedLat = locMatch.groupValues[1].toDoubleOrNull()
                        val parsedLon = locMatch.groupValues[2].toDoubleOrNull()
                        // Treat 0.0,0.0 as no location (location was off)
                        if (parsedLat != null && parsedLon != null &&
                            !(parsedLat == 0.0 && parsedLon == 0.0)
                        ) {
                            lat = parsedLat
                            lon = parsedLon
                        }
                    }
                }

                JsonMemoryEntry(
                    date = date,
                    dateOnly = dateOnly,
                    mediaType = mediaType,
                    latitude = lat,
                    longitude = lon
                )
            }
        }.getOrElse { e ->
            throw IllegalArgumentException("Failed to parse memories_history.json: ${e.message}", e)
        }
    }
}
