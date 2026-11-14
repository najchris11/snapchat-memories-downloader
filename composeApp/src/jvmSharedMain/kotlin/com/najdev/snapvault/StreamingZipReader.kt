package com.najdev.snapvault

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

internal const val MAX_STREAMED_HISTORY_BYTES = 64 * 1024 * 1024

/** Reopens a document for each operation; never makes a local copy of the ZIP archive. */
class StreamingZipReader(
    private val maxTextBytes: Int = MAX_STREAMED_HISTORY_BYTES,
    private val open: () -> InputStream,
) {
    fun entries(): List<String> = withZip { zip ->
        buildList {
            while (true) {
                val entry = zip.nextEntry ?: break
                add(entry.name)
                zip.closeEntry()
            }
        }
    }

    fun timestamps(): Map<String, Long> = withZip { zip ->
        buildMap {
            while (true) {
                val entry = zip.nextEntry ?: break
                extendedTimestampSeconds(entry.extra)?.let { put(entry.name, it) }
                zip.closeEntry()
            }
        }
    }

    fun readText(name: String): String? = withZip { zip ->
        var entry = zip.nextEntry
        while (entry != null && entry.name != name) {
            zip.closeEntry()
            entry = zip.nextEntry
        }
        if (entry == null) return@withZip null
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > maxTextBytes) throw IOException("History entry exceeds the memory limit")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }

    fun forEachEntry(block: (java.util.zip.ZipEntry, InputStream) -> Unit) = withZip { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            block(entry, zip)
            zip.closeEntry()
        }
    }

    private fun <T> withZip(block: (ZipInputStream) -> T): T = open().buffered().use { input ->
        input.mark(4)
        val header = ByteArray(4)
        DataInputStream(input).readFully(header)
        input.reset()
        val signature = header.map { it.toInt() and 0xFF }
        if (signature !in listOf(listOf(0x50, 0x4B, 3, 4), listOf(0x50, 0x4B, 5, 6))) {
            throw IOException("Not a supported ZIP document")
        }
        ZipInputStream(input).use(block)
    }
}

data class DocumentZipEntry(val name: String, val extra: ByteArray? = null)

/** Android installs its application-scoped ContentResolver opener; desktop uses paths. */
object JvmDocumentSources {
    @Volatile var open: ((String) -> InputStream)? = null
    @Volatile var directory: ((String) -> List<DocumentZipEntry>?)? = null
}

internal fun documentZipReader(path: String): StreamingZipReader? {
    if (!path.startsWith("content://")) return null
    return StreamingZipReader { JvmDocumentSources.open?.invoke(path) ?: throw IOException("Document access is unavailable") }
}
