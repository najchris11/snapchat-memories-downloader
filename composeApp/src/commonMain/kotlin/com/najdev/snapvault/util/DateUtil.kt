package com.najdev.snapvault.util

data class ParsedDateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int = 0,
    val minute: Int = 0,
    val second: Int = 0
) {
    fun toExifFormat(): String {
        val y = year.toString().padStart(4, '0')
        val m = month.toString().padStart(2, '0')
        val d = day.toString().padStart(2, '0')
        val h = hour.toString().padStart(2, '0')
        val min = minute.toString().padStart(2, '0')
        val s = second.toString().padStart(2, '0')
        return "$y:$m:$d $h:$min:$s"
    }

    fun toEpochMillis(): Long {
        // Simplified UTC epoch calculation avoiding platform-specific date libs
        val y = if (month <= 2) year - 1 else year
        val m = if (month <= 2) month + 12 else month
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m - 3) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097 + doe - 719468

        val secondsOfDay = hour * 3600L + minute * 60L + second
        return (days * 86400L + secondsOfDay) * 1000L
    }
}

object DateUtil {
    private val regexYmd = Regex("""(\d{4})[-:/](\d{2})[-:/](\d{2})(?:\s+(\d{2}):(\d{2}):(\d{2}))?""")
    private val regexDmy = Regex("""(\d{2})\.(\d{2})\.(\d{4})(?:\s+(\d{2}):(\d{2}):(\d{2}))?""")

    fun parse(dateStr: String?): ParsedDateTime? {
        if (dateStr == null) return null
        val cleaned = dateStr.replace("UTC", "").trim()

        // Match YYYY-MM-DD / YYYY:MM:DD / YYYY/MM/DD
        val matchYmd = regexYmd.find(cleaned)
        if (matchYmd != null) {
            val y = matchYmd.groupValues[1].toIntOrNull() ?: return null
            val m = matchYmd.groupValues[2].toIntOrNull() ?: return null
            val d = matchYmd.groupValues[3].toIntOrNull() ?: return null
            val h = matchYmd.groupValues[4].toIntOrNull() ?: 0
            val min = matchYmd.groupValues[5].toIntOrNull() ?: 0
            val s = matchYmd.groupValues[6].toIntOrNull() ?: 0
            return ParsedDateTime(y, m, d, h, min, s)
        }

        // Match DD.MM.YYYY
        val matchDmy = regexDmy.find(cleaned)
        if (matchDmy != null) {
            val d = matchDmy.groupValues[1].toIntOrNull() ?: return null
            val m = matchDmy.groupValues[2].toIntOrNull() ?: return null
            val y = matchDmy.groupValues[3].toIntOrNull() ?: return null
            val h = matchDmy.groupValues[4].toIntOrNull() ?: 0
            val min = matchDmy.groupValues[5].toIntOrNull() ?: 0
            val s = matchDmy.groupValues[6].toIntOrNull() ?: 0
            return ParsedDateTime(y, m, d, h, min, s)
        }

        return null
    }

    fun toExifDateString(dateStr: String?): String? = parse(dateStr)?.toExifFormat()

    fun toEpochMillis(dateStr: String?): Long? = parse(dateStr)?.toEpochMillis()
}
