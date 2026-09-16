package com.najdev.snapvault.downloader

import com.najdev.snapvault.model.MemoryItem
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DownloadEngineTest {

    private fun engine() = HttpClient(MockEngine { respondOk() })

    // ── Date parsing ────────────────────────────────────────────────────────

    @Test
    fun testDateParsing_isoWithTime() {
        val downloader = DownloadEngine(engine(), FakeFileSystem())
        assertEquals("2023-10-12_153000", downloader.parseDateToFilenamePrefix("2023-10-12 15:30:00 UTC"))
        assertEquals("2023-10-12_153000", downloader.parseDateToFilenamePrefix("2023-10-12 15:30:00"))
    }

    @Test
    fun testDateParsing_isoDateOnly() {
        val downloader = DownloadEngine(engine(), FakeFileSystem())
        assertEquals("2023-10-12_000000", downloader.parseDateToFilenamePrefix("2023-10-12"))
    }

    @Test
    fun testDateParsing_europeanFormat() {
        val downloader = DownloadEngine(engine(), FakeFileSystem())
        assertEquals("2023-10-12_153000", downloader.parseDateToFilenamePrefix("12.10.2023 15:30:00"))
        assertEquals("2023-10-12_000000", downloader.parseDateToFilenamePrefix("12.10.2023"))
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
        assertEquals("2023-10-12_153000_abc-123.jpg", downloader.buildFilename(item, null))
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
    fun testResumeDetects_jpegWithLegacyCompactPrefix() = runTest {
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
        val result = downloader.downloadFile(item, outDir.toString())

        assertEquals("skipped", result.status)
        assertTrue(result.item.downloadedPath!!.endsWith("20231012_153000_abc-123.jpeg"))
    }

    // ── Atomic writes (B5) ──────────────────────────────────────────────────

    @Test
    fun testDownloadWritesFinalFileAndNoPartRemains() = runTest {
        val fs = FakeFileSystem()
        val client = HttpClient(MockEngine {
            respond("file-bytes", headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
        })
        val downloader = DownloadEngine(client, fs)
        val item = MemoryItem(
            id = "abc-123",
            url = "https://media.com/photo.jpg?mid=abc-123",
            isGet = true,
            dateStr = "2023-10-12 15:30:00 UTC"
        )

        val result = downloader.downloadFile(item, "/output")

        assertEquals("downloaded", result.status)
        assertTrue(result.item.isDownloaded)
        val outFiles = fs.list("/output".toPath()).map { it.name }
        assertTrue("2023-10-12_153000_abc-123.jpg" in outFiles, "final file must exist, got: $outFiles")
        assertTrue(outFiles.none { it.endsWith(".part") }, "no temp file may remain, got: $outFiles")
        assertEquals("file-bytes", fs.read("/output/2023-10-12_153000_abc-123.jpg".toPath()) { readUtf8() })
    }

    @Test
    fun testFailedDownloadLeavesNoPartialFile() = runTest {
        val fs = FakeFileSystem()
        var sentBody = false
        val client = HttpClient(MockEngine {
            sentBody = true
            respondError(HttpStatusCode.InternalServerError)
        })
        val downloader = DownloadEngine(client, fs)
        val item = MemoryItem(
            id = "bad-999",
            url = "https://media.com/photo.jpg?mid=bad-999",
            isGet = true,
            dateStr = null
        )

        val result = downloader.downloadFile(item, "/output")

        assertTrue(sentBody)
        assertTrue(result.status.startsWith("error"))
        assertFalse(result.item.isDownloaded)
        val outFiles = fs.list("/output".toPath()).map { it.name }
        assertTrue(outFiles.isEmpty(), "a failed download must leave nothing behind, got: $outFiles")
    }

    // ── Colliding download identities (D08) ─────────────────────────────────

    private fun photoRow(url: String) = MemoryItem(
        id = "abc-123",
        url = url,
        isGet = true,
        dateStr = "2023-10-12 15:30:00 UTC",
    )

    private val photoName = "2023-10-12_153000_abc-123.jpg"

    // D08: a Snapchat export repeats rows, and every repeat derives the same id from the same
    // `mid` — so they build the same filename, and downloadAll used to schedule both. With
    // more than one worker that is two responses streaming into one `<name>.part` and
    // whichever finishes last committing the interleaving as if it were a file.
    @Test
    fun aRepeatedExportRowIsDownloadedOnceAndCommittedIntact() = runTest {
        val fs = FakeFileSystem()
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond("photo-bytes", headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
        })
        val row = photoRow("https://media.com/photo.jpg?mid=abc-123")

        val results = DownloadEngine(client, fs).downloadAll(listOf(row, row), "/output", workers = 4)

        assertEquals(1, requests, "a row repeated in the export is one file, not two downloads")
        assertEquals(
            listOf(photoName),
            fs.list("/output".toPath()).map { it.name },
            "one row, one file — and no temp file left over",
        )
        assertEquals("photo-bytes", fs.read("/output/$photoName".toPath()) { readUtf8() })

        // Every row still has to be accounted for: the progress total is the export's row
        // count, so a row that produces no result of its own stalls the bar short of 100%.
        assertEquals(2, results.size)
        assertEquals(listOf("downloaded", "skipped"), results.map { it.status })
        assertTrue(
            results.all { it.item.downloadedPath == "/output/$photoName" },
            "both rows point at the file that was actually written, got: ${results.map { it.item.downloadedPath }}",
        )
    }

    // The other half of D08: same identity, different links. These are not obviously the same
    // media, so silently letting one overwrite the other is the worst available answer — the
    // user is never told that a memory they exported was dropped.
    @Test
    fun twoRowsWithOneIdentityButDifferentLinksDoNotSilentlyPickAWinner() = runTest {
        val fs = FakeFileSystem()
        val served = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            served += request.url.toString()
            respond(
                "bytes-for-${request.url.parameters["v"]}",
                headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
            )
        })
        val first = photoRow("https://media.com/photo.jpg?mid=abc-123&v=1")
        val second = photoRow("https://media.com/photo.jpg?mid=abc-123&v=2")

        val results = DownloadEngine(client, fs).downloadAll(listOf(first, second), "/output", workers = 4)

        assertEquals(1, served.size, "the conflicting row must not be fetched at all, got: $served")
        assertEquals("downloaded", results[0].status)
        assertTrue(
            results[1].status.startsWith("error"),
            "the losing row has to be reported as a failure, was: ${results[1].status}",
        )
        assertTrue(
            results[1].status.contains(photoName),
            "the failure must name the file both rows wanted, was: ${results[1].status}",
        )
        assertFalse(results[1].item.isDownloaded)
        assertEquals("bytes-for-1", fs.read("/output/$photoName".toPath()) { readUtf8() })
        assertEquals(listOf(photoName), fs.list("/output".toPath()).map { it.name })
    }

    // Defence behind the grouping above: even if two writers do reach the commit for one name,
    // the second must not replace a finished file with its own. An overwrite here is
    // indistinguishable from a successful download, which is how the corruption stayed
    // invisible.
    @Test
    fun aFileThatAppearedDuringADownloadIsNotOverwritten() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories("/output".toPath())
        val client = HttpClient(MockEngine {
            // Somebody else finishes this exact file while our response is in flight.
            fs.write("/output/$photoName".toPath()) { writeUtf8("the-other-writers-bytes") }
            respond("our-bytes", headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
        })

        val result = DownloadEngine(client, fs)
            .downloadFile(photoRow("https://media.com/photo.jpg?mid=abc-123"), "/output")

        assertTrue(result.status.startsWith("error"), "was: ${result.status}")
        assertEquals("the-other-writers-bytes", fs.read("/output/$photoName".toPath()) { readUtf8() })
        val outFiles = fs.list("/output".toPath()).map { it.name }
        assertTrue(outFiles.none { it.endsWith(".part") }, "no temp file may remain, got: $outFiles")
    }

    // Companion case: grouping by destination must not start collapsing distinct memories.
    @Test
    fun rowsForDifferentMemoriesAreAllStillDownloaded() = runTest {
        val fs = FakeFileSystem()
        var requests = 0
        val client = HttpClient(MockEngine {
            requests++
            respond("bytes", headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
        })
        val items = listOf(
            MemoryItem(id = "aaa", url = "https://media.com/a.jpg?mid=aaa", isGet = true, dateStr = "2023-10-12 15:30:00 UTC"),
            MemoryItem(id = "bbb", url = "https://media.com/b.jpg?mid=bbb", isGet = true, dateStr = "2023-10-12 15:30:00 UTC"),
            // Same memory id, different capture time — a different file, not a repeat.
            MemoryItem(id = "aaa", url = "https://media.com/a.jpg?mid=aaa", isGet = true, dateStr = "2023-10-13 15:30:00 UTC"),
        )

        val results = DownloadEngine(client, fs).downloadAll(items, "/output", workers = 4)

        assertEquals(3, requests)
        assertEquals(listOf("downloaded", "downloaded", "downloaded"), results.map { it.status })
        assertEquals(3, fs.list("/output".toPath()).size)
    }
}
