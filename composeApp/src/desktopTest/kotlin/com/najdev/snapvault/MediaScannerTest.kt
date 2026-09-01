package com.najdev.snapvault

import java.io.File
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
}
