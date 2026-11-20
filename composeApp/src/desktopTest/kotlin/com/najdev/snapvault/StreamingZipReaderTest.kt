package com.najdev.snapvault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamingZipReaderTest {
    @Test
    fun documentsAreReadFromReopenableStreamsWithoutLocalArchiveCopies() {
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry("json/memories_history.json"))
                zip.write("sample history".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("memories/2024-01-01_AAA-main.jpg"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }.toByteArray()
        var opened = 0
        val reader = StreamingZipReader { opened++; ByteArrayInputStream(bytes) }
        assertEquals(listOf("json/memories_history.json", "memories/2024-01-01_AAA-main.jpg"), reader.entries())
        assertEquals("sample history", reader.readText("json/memories_history.json"))
        assertNull(reader.readText("missing"))
        assertEquals(3, opened)
    }
    @Test
    fun corruptDocumentsAndOversizedHistoryAreRejected() {
        assertEquals(67_108_864, MAX_STREAMED_HISTORY_BYTES)
        kotlin.test.assertFailsWith<java.io.IOException> { StreamingZipReader { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }.entries() }
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry("history.json")); zip.write("too long".toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        kotlin.test.assertFailsWith<java.io.IOException> { StreamingZipReader(maxTextBytes = 3) { ByteArrayInputStream(bytes) }.readText("history.json") }
    }
    @Test
    fun seekableDocumentsUseCentralDirectoryTimestampsWithoutReadingMediaPayloads() {
        val oldOpen = JvmDocumentSources.open
        val oldDirectory = JvmDocumentSources.directory
        var payloadReads = 0
        val seconds = 1_704_067_200L
        val extra = java.nio.ByteBuffer.allocate(9).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putShort(0x5455.toShort()).putShort(5.toShort()).put(1.toByte()).putInt(seconds.toInt()).array()
        try {
            JvmDocumentSources.open = { payloadReads++; ByteArrayInputStream(byteArrayOf()) }
            JvmDocumentSources.directory = { listOf(DocumentZipEntry("memories/a.jpg", extra)) }
            assertEquals(listOf("memories/a.jpg"), listZipEntries("content://documents/central-only"))
            assertEquals(mapOf("memories/a.jpg" to seconds), listZipEntryTimestamps("content://documents/central-only"))
            assertEquals(0, payloadReads)
        } finally {
            JvmDocumentSources.open = oldOpen
            JvmDocumentSources.directory = oldDirectory
        }
    }
}
