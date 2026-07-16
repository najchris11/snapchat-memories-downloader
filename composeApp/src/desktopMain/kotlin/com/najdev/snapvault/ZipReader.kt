package com.najdev.snapvault

actual fun listZipEntries(zipFilePath: String): List<String> {
    return try {
        java.util.zip.ZipFile(zipFilePath).use { zf ->
            zf.entries().toList().map { it.name }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

actual fun readZipEntryText(zipFilePath: String, entryName: String): String? {
    return try {
        java.util.zip.ZipFile(zipFilePath).use { zf ->
            val entry = zf.getEntry(entryName) ?: return null
            zf.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    } catch (e: Exception) {
        null
    }
}

actual fun listZipEntryTimestamps(zipFilePath: String): Map<String, Long> {
    return try {
        java.util.zip.ZipFile(zipFilePath).use { zf ->
            val result = mutableMapOf<String, Long>()
            for (entry in zf.entries()) {
                extendedTimestampSeconds(entry.extra)?.let { seconds -> result[entry.name] = seconds }
            }
            result
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

// Parses the Info-ZIP "UT" extended timestamp extra field (0x5455): a block of
// [2-byte header id][2-byte size][1-byte flags][optional 4-byte LE mod time, ...].
// We only need the modification time, guarded by flags bit 0. See PKWARE's APPNOTE.TXT
// section 4.5.7 / Info-ZIP's ziptime.c for the format.
private fun extendedTimestampSeconds(extra: ByteArray?): Long? {
    if (extra == null || extra.size < 4) return null
    val buf = java.nio.ByteBuffer.wrap(extra).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    while (buf.remaining() >= 4) {
        val headerId = buf.short.toInt() and 0xFFFF
        val size = buf.short.toInt() and 0xFFFF
        if (size > buf.remaining()) return null
        val blockEnd = buf.position() + size
        if (headerId == 0x5455 && size >= 5) {
            val flags = buf.get().toInt()
            if (flags and 0x1 != 0) {
                return buf.int.toLong()
            }
        }
        buf.position(blockEnd)
    }
    return null
}
