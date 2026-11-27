package com.najdev.snapvault

actual fun listZipEntries(zipFilePath: String): List<String> {
    return try {
        if (zipFilePath.startsWith("content://")) {
            JvmDocumentSources.directory?.invoke(zipFilePath)?.let { return it.map(DocumentZipEntry::name) }
        }
        documentZipReader(zipFilePath)?.let { return it.entries() }
        java.util.zip.ZipFile(zipFilePath).use { zf ->
            zf.entries().toList().map { it.name }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

actual fun readZipEntryText(zipFilePath: String, entryName: String): String? {
    return try {
        documentZipReader(zipFilePath)?.let { return it.readText(entryName) }
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
        if (zipFilePath.startsWith("content://")) {
            JvmDocumentSources.directory?.invoke(zipFilePath)?.let { entries ->
                return entries.mapNotNull { entry -> extendedTimestampSeconds(entry.extra)?.let { entry.name to it } }.toMap()
            }
        }
        documentZipReader(zipFilePath)?.let { return it.timestamps() }
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
internal fun extendedTimestampSeconds(extra: ByteArray?): Long? {
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

internal actual fun listZipWebpOverlays(zipFilePath: String): Set<String> = runCatching {
    buildSet {
        val document = documentZipReader(zipFilePath)
        if (document != null) {
            document.forEachEntry { entry, input ->
                if (isZipPngOverlay(entry.name) && isWebpHeader(input.zipEntryHeader())) add(entry.name)
            }
        } else {
            java.util.zip.ZipFile(zipFilePath).use { zip ->
                for (entry in zip.entries()) {
                    if (isZipPngOverlay(entry.name) &&
                        zip.getInputStream(entry).use { isWebpHeader(it.zipEntryHeader()) }
                    ) add(entry.name)
                }
            }
        }
    }
}.getOrDefault(emptySet())

// InputStream.readNBytes is unavailable on older Android API levels.
private fun java.io.InputStream.zipEntryHeader(): ByteArray {
    val header = ByteArray(12)
    var offset = 0
    while (offset < header.size) {
        val count = read(header, offset, header.size - offset)
        if (count < 0) return header.copyOf(offset)
        offset += count
    }
    return header
}
