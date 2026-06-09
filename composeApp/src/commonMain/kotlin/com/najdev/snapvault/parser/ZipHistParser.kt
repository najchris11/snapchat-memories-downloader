package com.najdev.snapvault.parser

data class HistMemoryEntry(
    val fullDateTime: String,
    val date: String,
    val mediaType: String,
    val latitude: Double?,
    val longitude: Double?
)

object ZipHistParser {

    private val rowRegex = Regex(
        """<tr><td>(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} UTC)</td><td>(Image|Video)</td><td>([^<]*)</td><td>.*?</td></tr>"""
    )

    private val locationRegex = Regex(
        """Latitude,\s*Longitude:\s*([+-]?\d+\.?\d*),\s*([+-]?\d+\.?\d*)"""
    )

    fun parse(html: String): List<HistMemoryEntry> {
        return rowRegex.findAll(html).mapNotNull { m ->
            val datetime = m.groupValues[1]
            val mediaType = m.groupValues[2]
            val location = m.groupValues[3]
            val date = datetime.take(10)

            val loc = locationRegex.find(location)
            var lat: Double? = null
            var lon: Double? = null
            if (loc != null) {
                val la = loc.groupValues[1].toDoubleOrNull()
                val lo = loc.groupValues[2].toDoubleOrNull()
                if (la != null && lo != null && !(la == 0.0 && lo == 0.0)) {
                    lat = la
                    lon = lo
                }
            }

            HistMemoryEntry(
                fullDateTime = datetime,
                date = date,
                mediaType = mediaType,
                latitude = lat,
                longitude = lon
            )
        }.toList()
    }
}