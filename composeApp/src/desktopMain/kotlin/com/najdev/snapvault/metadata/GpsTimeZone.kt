package com.najdev.snapvault.metadata

import net.iakovlev.timeshape.TimeZoneEngine
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

internal data class GpsCaptureTime(val exifLocalDateTime: String, val offset: String)

private val timeZoneEngine by lazy { runCatching { TimeZoneEngine.initialize(false) }.getOrNull() }
private val utcCaptureFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'").withResolverStyle(ResolverStyle.STRICT)
private val exifCaptureFormat = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss")

/** Exact boundary lookup, then IANA historical offset. No longitude rounding or network. */
internal fun gpsCaptureTime(dateStr: String?, latitude: Double, longitude: Double): GpsCaptureTime? {
    if (dateStr == null || !latitude.isFinite() || !longitude.isFinite()) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    val instant = runCatching { LocalDateTime.parse(dateStr, utcCaptureFormat).toInstant(ZoneOffset.UTC) }.getOrNull() ?: return null
    val zone = timeZoneEngine?.queryAll(latitude, longitude)?.singleOrNull() ?: return null
    val local = instant.atZone(zone)
    return GpsCaptureTime(local.format(exifCaptureFormat), local.offset.id.takeUnless { it == "Z" } ?: "+00:00")
}

/** Date-only/UTC rewrites clear prior local offsets rather than leaving mismatched tags. */
internal fun imageDateArguments(exifDate: String, offset: String? = null): List<String> = listOf(
    "-DateTimeOriginal=$exifDate", "-CreateDate=$exifDate", "-ModifyDate=$exifDate",
    "-OffsetTimeOriginal=${offset.orEmpty()}", "-OffsetTimeDigitized=${offset.orEmpty()}", "-OffsetTime=${offset.orEmpty()}",
)
