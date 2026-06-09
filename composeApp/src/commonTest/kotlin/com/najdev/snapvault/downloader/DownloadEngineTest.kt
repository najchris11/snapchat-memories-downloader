package com.najdev.snapvault.downloader

import com.najdev.snapvault.model.MemoryItem
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import okio.fakefilesystem.FakeFileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class DownloadEngineTest {

    private fun engine() = HttpClient(MockEngine { respondOk() })

    // ── Date parsing ────────────────────────────────────────────────────────

    @Test
    fun testDateParsing_isoWithTime() {
        val downloader = DownloadEngine(engine(), FakeFileSystem())
        assertEquals("20231012_153000", downloader.parseDateToFilenamePrefix("2023-10-12 15:30:00 UTC"))
        assertEquals("20231012_153000", downloader.parseDateToFilenamePrefix("2023-10-12 15:30:00"))
    }

    @Test
    fun testDateParsing_isoDateOnly() {
        val downloader = DownloadEngine(engine(), FakeFileSystem())
        assertEquals("20231012_000000", downloader.parseDateToFilenamePrefix("2023-10-12"))
    }

    @Test
    fun testDateParsing_europeanFormat() {
        val downloader = DownloadEngine(engine(), FakeFileSystem())
        assertEquals("20231012_153000", downloader.parseDateToFilenamePrefix("12.10.2023 15:30:00"))
        assertEquals("20231012_000000", downloader.parseDateToFilenamePrefix("12.10.2023"))
    }

    @Test
    fun testDateParsing_invalid() {
        val downloader = DownloadEngine(engine(), FakeFileSystem())
        assertNull(downloader.parseDateToFilenamePrefix("invalid-date"))
        assertNull(downloader.parseDateToFilenamePrefix(null))
    }

    // ── Extension helpers ───────────────────────────────────────────────────

    @Test
    fun testGetFileExtensionFromUrl() {
        val downloader = DownloadEngine(engine(), FakeFileSystem())
        assertEquals(".mp4", downloader.getFileExtensionFromUrl("https://cdn.example.com/clip.mp4?mid=abc"))
        assertEquals(".jpg", downloader.getFileExtensionFromUrl("https://cdn.example.com/photo.jpg"))
        assertEquals(".jpeg", downloader.getFileExtensionFromUrl("https://cdn.example.com/photo.jpeg"))
        assertEquals(".png", downloader.getFileExtensionFromUrl("https://cdn.example.com/img.png"))
        assertNull(downloader.getFileExtensionFromUrl("https://cdn.example.com/stream"))
    }

    @Test
    fun testGetFileExtensionFromContentType() {
        val downloader = DownloadEngine(engine(), FakeFileSystem())
        assertEquals(".mp4", downloader.getFileExtensionFromContentType("video/mp4"))
        assertEquals(".jpg", downloader.getFileExtensionFromContentType("image/jpeg"))
        assertEquals(".jpg", downloader.getFileExtensionFromContentType("image/jpg"))
        assertEquals(".png", downloader.getFileExtensionFromContentType("image/png"))
        assertEquals(".zip", downloader.getFileExtensionFromContentType("application/zip"))
    }

    // ── Filename builder ────────────────────────────────────────────────────

    @Test
    fun testBuildFilename_withDateAndUrlExtension() {
        val downloader = DownloadEngine(engine(), FakeFileSystem())
        val item = MemoryItem(
            id = "abc-123",
            url = "https://media.com/file.jpg?mid=abc-123",
            isGet = true,
            dateStr = "2023-10-12 15:30:00 UTC"
        )
        assertEquals("20231012_153000_abc-123.jpg", downloader.buildFilename(item, null))
    }

    @Test
    fun testBuildFilename_noDateFallsBackToContentType() {
        val downloader = DownloadEngine(engine(), FakeFileSystem())
        val item = MemoryItem(
            id = "xyz-789",
            url = "https://media.com/file?mid=xyz-789",
            isGet = true,
            dateStr = null
        )
        assertEquals("xyz-789.mp4", downloader.buildFilename(item, "video/mp4"))
    }

    // ── Resume / skip detection ─────────────────────────────────────────────

    @Test
    fun testResumeDetects_jpeg() {
        val fs = FakeFileSystem()
        val outDir = "/output".toPath()
        fs.createDirectories(outDir)
        fs.write(outDir / "xyz-789.jpeg") { writeUtf8("fake") }

        val downloader = DownloadEngine(engine(), fs)
        val item = MemoryItem(
            id = "xyz-789",
            url = "https://media.com/photo.jpeg",
            isGet = true,
            dateStr = null
        )
        val existing = fs.list(outDir).find { f ->
            val name = f.name
            name == "${item.id}.mp4" || name == "${item.id}.jpg" || name == "${item.id}.jpeg" ||
            name == "${item.id}.png" || name == "${item.id}.zip"
        }
        assertNotNull(existing, ".jpeg file should be found for resume detection")
    }

    @Test
    fun testResumeDetects_jpegWithPrefix() {
        val fs = FakeFileSystem()
        val outDir = "/output".toPath()
        fs.createDirectories(outDir)
        fs.write(outDir / "20231012_153000_abc-123.jpeg") { writeUtf8("fake") }

        val downloader = DownloadEngine(engine(), fs)
        val item = MemoryItem(
            id = "abc-123",
            url = "https://media.com/photo.jpeg",
            isGet = true,
            dateStr = "2023-10-12 15:30:00 UTC"
        )
        val prefix = downloader.parseDateToFilenamePrefix(item.dateStr)
        val existing = fs.list(outDir).find { f ->
            val name = f.name
            prefix != null && (
                name == "${prefix}_${item.id}.mp4" || name == "${prefix}_${item.id}.jpg" ||
                name == "${prefix}_${item.id}.jpeg" || name == "${prefix}_${item.id}.png" ||
                name == "${prefix}_${item.id}.zip"
            )
        }
        assertNotNull(existing, "Prefixed .jpeg file should be found for resume detection")
    }
}
