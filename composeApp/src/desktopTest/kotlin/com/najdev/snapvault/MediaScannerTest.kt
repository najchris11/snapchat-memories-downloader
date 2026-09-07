package com.najdev.snapvault

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Regression for BUG-18: MediaScanner's Library filter used to be a narrower,
// hand-maintained extension list than what the pipeline itself reads/writes tags for, so a
// -main.heic or -main.mkv with no overlay pair (kept its own extension) became invisible in
// the Library despite being a real, successfully imported file.
class MediaScannerTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = File.createTempFile("scanner-test", "").apply { delete(); mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun scanIncludesFormatsThePipelineProducesButDoesNotCombine() {
        File(dir, "2024-01-01_AAA-main.heic").writeBytes(byteArrayOf(1))
        File(dir, "2024-01-02_BBB-main.mkv").writeBytes(byteArrayOf(1))
        File(dir, "2024-01-03_CCC-main.webp").writeBytes(byteArrayOf(1))
        File(dir, "2024-01-04_DDD-main.m4v").writeBytes(byteArrayOf(1))

        val items = scanMediaFiles(dir.absolutePath)

        assertEquals(4, items.size, "every pipeline-supported format must be listed, got: ${items.map { it.title }}")
        val byTitle = items.associateBy { it.title }
        assertEquals("photo", byTitle.getValue("2024-01-01_AAA-main").type)
        assertEquals("video", byTitle.getValue("2024-01-02_BBB-main").type)
        assertEquals("photo", byTitle.getValue("2024-01-03_CCC-main").type)
        assertEquals("video", byTitle.getValue("2024-01-04_DDD-main").type)
    }

    @Test
    fun scanStillIncludesOriginallySupportedFormats() {
        File(dir, "2024-01-01_AAA.jpg").writeBytes(byteArrayOf(1))
        File(dir, "2024-01-02_BBB.mp4").writeBytes(byteArrayOf(1))
        File(dir, "2024-01-03_CCC.gif").writeBytes(byteArrayOf(1))

        val items = scanMediaFiles(dir.absolutePath)

        assertEquals(3, items.size)
        assertTrue(items.any { it.type == "photo" })
        assertTrue(items.any { it.type == "video" })
    }

    @Test
    fun scanIgnoresUnrelatedFiles() {
        File(dir, "vault_index.json").writeBytes(byteArrayOf(1))
        File(dir, "notes.txt").writeBytes(byteArrayOf(1))

        val items = scanMediaFiles(dir.absolutePath)

        assertTrue(items.isEmpty())
    }

    @Test
    fun scanUsesSnapVaultCaptureDateInsteadOfFilesystemModifiedDate() {
        val oldCapture = File(dir, "2021-02-03_memory-a.png").apply { writeBytes(byteArrayOf(1)) }
        val newCapture = File(dir, "2024-11-28_memory-b.png").apply { writeBytes(byteArrayOf(1)) }
        // Simulates copying/restoring a library: mtime no longer reflects capture order.
        Files.setLastModifiedTime(oldCapture.toPath(), FileTime.from(Instant.parse("2026-01-01T00:00:00Z")))
        Files.setLastModifiedTime(newCapture.toPath(), FileTime.from(Instant.parse("2001-01-01T00:00:00Z")))

        val items = scanMediaFiles(dir.absolutePath)

        assertEquals(listOf("2024-11-28_memory-b", "2021-02-03_memory-a"), items.map { it.title })
        assertEquals("NOV 28, 2024", items.first().date)
        assertEquals("FEB 03, 2021", items.last().date)
    }

    @Test
    fun scanFallsBackToFilesystemDateForNonSnapVaultNames() {
        File(dir, "holiday.png").writeBytes(byteArrayOf(1))

        val item = scanMediaFiles(dir.absolutePath).single()

        assertTrue(item.date.matches(Regex("[A-Z]{3} \\d{2}, \\d{4}")))
    }

    @Test
    fun scanSortsFilenameAndFilesystemDatesOnTheSameTimeline() {
        File(dir, "2024-06-01_memory.png").writeBytes(byteArrayOf(1))
        val ordinaryFile = File(dir, "ordinary.png").apply { writeBytes(byteArrayOf(1)) }
        Files.setLastModifiedTime(ordinaryFile.toPath(), FileTime.from(Instant.parse("2023-01-01T00:00:00Z")))

        assertEquals(
            listOf("2024-06-01_memory", "ordinary"),
            scanMediaFiles(dir.absolutePath).map { it.title }
        )
    }
}
