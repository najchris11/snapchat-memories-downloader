package com.najdev.snapvault.downloader

import com.najdev.snapvault.model.MemoryItem
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.ForwardingFileSystem
import okio.ForwardingSink
import okio.Path
import okio.Sink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
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

    // Every engine in these tests streams on a real-time context that runs one task at a time.
    // FakeFileSystem is not thread-safe — on the real IO dispatcher, concurrent workers threw
    // ConcurrentModificationException from inside it — while the stall bound still needs the
    // wall clock rather than the test scheduler's virtual time.
    private fun testEngine(
        client: HttpClient,
        fileSystem: FakeFileSystem,
        stallTimeoutMillis: Long = DEFAULT_STALL_TIMEOUT_MS,
    ) = DownloadEngine(client, fileSystem, stallTimeoutMillis, Dispatchers.Default.limitedParallelism(1))

    private fun engine() = HttpClient(MockEngine { respondOk() })

    // ── Date parsing ────────────────────────────────────────────────────────

    @Test
    fun testDateParsing_isoWithTime() {
        val downloader = testEngine(engine(), FakeFileSystem())
        assertEquals("2023-10-12_153000", downloader.parseDateToFilenamePrefix("2023-10-12 15:30:00 UTC"))
        assertEquals("2023-10-12_153000", downloader.parseDateToFilenamePrefix("2023-10-12 15:30:00"))
    }

    @Test
    fun testDateParsing_isoDateOnly() {
        val downloader = testEngine(engine(), FakeFileSystem())
        assertEquals("2023-10-12_000000", downloader.parseDateToFilenamePrefix("2023-10-12"))
    }

    @Test
    fun testDateParsing_europeanFormat() {
        val downloader = testEngine(engine(), FakeFileSystem())
        assertEquals("2023-10-12_153000", downloader.parseDateToFilenamePrefix("12.10.2023 15:30:00"))
        assertEquals("2023-10-12_000000", downloader.parseDateToFilenamePrefix("12.10.2023"))
    }

    @Test
    fun testDateParsing_invalid() {
        val downloader = testEngine(engine(), FakeFileSystem())
        assertNull(downloader.parseDateToFilenamePrefix("invalid-date"))
        assertNull(downloader.parseDateToFilenamePrefix(null))
    }

    // ── Extension helpers ───────────────────────────────────────────────────

    @Test
    fun testGetFileExtensionFromUrl() {
        val downloader = testEngine(engine(), FakeFileSystem())
        assertEquals(".mp4", downloader.getFileExtensionFromUrl("https://cdn.example.com/clip.mp4?mid=abc"))
        assertEquals(".jpg", downloader.getFileExtensionFromUrl("https://cdn.example.com/photo.jpg"))
        assertEquals(".jpeg", downloader.getFileExtensionFromUrl("https://cdn.example.com/photo.jpeg"))
        assertEquals(".png", downloader.getFileExtensionFromUrl("https://cdn.example.com/img.png"))
        assertNull(downloader.getFileExtensionFromUrl("https://cdn.example.com/stream"))
    }

    @Test
    fun testGetFileExtensionFromContentType() {
        val downloader = testEngine(engine(), FakeFileSystem())
        assertEquals(".mp4", downloader.getFileExtensionFromContentType("video/mp4"))
        assertEquals(".jpg", downloader.getFileExtensionFromContentType("image/jpeg"))
        assertEquals(".jpg", downloader.getFileExtensionFromContentType("image/jpg"))
        assertEquals(".png", downloader.getFileExtensionFromContentType("image/png"))
        assertEquals(".zip", downloader.getFileExtensionFromContentType("application/zip"))
    }

    // ── Filename builder ────────────────────────────────────────────────────

    @Test
    fun testBuildFilename_withDateAndUrlExtension() {
        val downloader = testEngine(engine(), FakeFileSystem())
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
        val downloader = testEngine(engine(), FakeFileSystem())
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

        val downloader = testEngine(engine(), fs)
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

        val downloader = testEngine(engine(), fs)
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
        val downloader = testEngine(client, fs)
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
        val downloader = testEngine(client, fs)
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

        val results = testEngine(client, fs).downloadAll(listOf(row, row), "/output", workers = 4)

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

        val results = testEngine(client, fs).downloadAll(listOf(first, second), "/output", workers = 4)

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

        val result = testEngine(client, fs)
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

        val results = testEngine(client, fs).downloadAll(items, "/output", workers = 4)

        assertEquals(3, requests)
        assertEquals(listOf("downloaded", "downloaded", "downloaded"), results.map { it.status })
        assertEquals(3, fs.list("/output".toPath()).size)
    }

    // ── Resume must mean "finished", not "exists" (D14) ─────────────────────

    // D14: resume trusted any file under the right name. An empty file — a crash at the wrong
    // moment, a sync client's placeholder — was skipped on every run from then on, and the
    // Library showed a memory that would not open.
    @Test
    fun anEmptyFileUnderTheNameIsDownloadedAgainRatherThanSkipped() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories("/output".toPath())
        fs.write("/output/$photoName".toPath()) { }
        val client = HttpClient(MockEngine {
            respond(JPEG_BYTES, headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
        })

        val result = testEngine(client, fs).downloadFile(photoRow("https://media.com/photo.jpg?mid=abc-123"), "/output")

        assertEquals("downloaded", result.status)
        assertEquals(JPEG_BYTES, fs.read("/output/$photoName".toPath()) { readUtf8() })
    }

    // The forever-skip this finding is really about. An expired link answers 200 with a web
    // page, and older builds saved that page under a media name. Resume then found "the file"
    // and never fetched it again, so the one memory that most needed a retry never got one.
    @Test
    fun aSavedErrorPageUnderTheNameIsDownloadedAgainRatherThanSkipped() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories("/output".toPath())
        fs.write("/output/$photoName".toPath()) { writeUtf8("<!DOCTYPE html><html><body>Link expired</body></html>") }
        val client = HttpClient(MockEngine {
            respond(JPEG_BYTES, headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
        })

        val result = testEngine(client, fs).downloadFile(photoRow("https://media.com/photo.jpg?mid=abc-123"), "/output")

        assertEquals("downloaded", result.status)
        assertEquals(JPEG_BYTES, fs.read("/output/$photoName".toPath()) { readUtf8() })
    }

    // How that page got saved in the first place: a 2xx is not proof of media. An unknown
    // content type fell back to ".mp4", so the page was written as a video that no player
    // opens — and, per the test above, never retried.
    @Test
    fun aLinkThatAnswersWithAWebPageIsAFailureNotAFile() = runTest {
        val fs = FakeFileSystem()
        val client = HttpClient(MockEngine {
            // Plain words rather than markup, so only the header can give it away — the body
            // check is covered by its own test below.
            respond(
                "This link has expired.",
                headers = headersOf(HttpHeaders.ContentType, "text/plain; charset=utf-8"),
            )
        })
        val row = MemoryItem(id = "vid-1", url = "https://media.com/stream?mid=vid-1", isGet = true, dateStr = null)

        val result = testEngine(client, fs).downloadFile(row, "/output")

        assertTrue(result.status.startsWith("error"), "was: ${result.status}")
        assertTrue("expired" in result.status, "the failure has to suggest why, was: ${result.status}")
        assertFalse(result.item.isDownloaded)
        assertEquals(emptyList(), fs.list("/output".toPath()).map { it.name }, "nothing may be committed")
    }

    // The content type is the server's claim, not the body's. A page served as a generic
    // binary is still a page.
    @Test
    fun aWebPageBodyIsRefusedEvenWhenTheHeaderDoesNotSaySo() = runTest {
        val fs = FakeFileSystem()
        val client = HttpClient(MockEngine {
            respond(
                "  \n<html><body>Not found</body></html>",
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
            )
        })

        val result = testEngine(client, fs).downloadFile(photoRow("https://media.com/photo.jpg?mid=abc-123"), "/output")

        assertTrue(result.status.startsWith("error"), "was: ${result.status}")
        assertEquals(emptyList(), fs.list("/output".toPath()).map { it.name })
    }

    // A response that stops sending bytes and never closes held its worker forever — and with
    // a few of those, every worker, so the run sat at the same percentage with no error to
    // show. Stalling has to fail that one download and let the rest carry on.
    @Test
    fun aStalledResponseFailsItsDownloadAndTheRunCarriesOn() = runTest {
        val fs = FakeFileSystem()
        val client = HttpClient(MockEngine { request ->
            if ("stalls" in request.url.toString()) {
                val body = ByteChannel()
                body.writeStringUtf8("the first few bytes")
                body.flush()
                respond(body, headers = headersOf(HttpHeaders.ContentType, "video/mp4"))
            } else {
                respond(JPEG_BYTES, headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
            }
        })
        val stalls = MemoryItem(id = "stuck", url = "https://media.com/stalls.mp4?mid=stuck", isGet = true, dateStr = null)
        val fine = MemoryItem(id = "fine", url = "https://media.com/fine.jpg?mid=fine", isGet = true, dateStr = null)

        // Real milliseconds: the engine measures stalls on the wall clock the network runs on,
        // not the test scheduler's virtual time, so this is kept short on purpose.
        val results = testEngine(client, fs, stallTimeoutMillis = 500)
            .downloadAll(listOf(stalls, fine), "/output", workers = 1)

        assertTrue(results[0].status.startsWith("error"), "was: ${results[0].status}")
        assertEquals("downloaded", results[1].status, "one stalled link must not stop the rest of the run")
        assertEquals(listOf("fine.jpg"), fs.list("/output".toPath()).map { it.name }, "no partial file may remain")
    }
    // The other half of a stall: a server that accepts the request and never answers. The
    // body bound cannot see this — nothing has been handed to the caller yet to read.
    @Test
    fun aServerThatNeverAnswersFailsItsDownloadRatherThanHangingTheRun() = runTest {
        val fs = FakeFileSystem()
        val client = HttpClient(MockEngine { awaitCancellation() })

        val result = testEngine(client, fs, stallTimeoutMillis = 500)
            .downloadFile(photoRow("https://media.com/photo.jpg?mid=abc-123"), "/output")

        assertTrue(result.status.startsWith("error"), "was: ${result.status}")
        assertEquals(emptyList(), fs.list("/output".toPath()).map { it.name })
    }

    // D13: a download has to reach disk as it arrives. Executed the plain way, Ktor saves the
    // whole response in memory before handing it over, so every concurrent worker could be
    // holding an entire video at once. Here the server sends half the body and will not send
    // the rest until those bytes are in the partial file — a client that buffers never writes
    // anything until the end, and this times out.
    @Test
    fun aDownloadReachesDiskWhileTheResponseIsStillArriving() = runTest {
        val chunk = ByteArray(64 * 1024) { 7 }
        val firstChunkOnDisk = CompletableDeferred<Unit>()
        val fs = object : ForwardingFileSystem(FakeFileSystem()) {
            override fun sink(file: Path, mustCreate: Boolean): Sink {
                val real = super.sink(file, mustCreate)
                if (!file.name.endsWith(".part")) return real
                return object : ForwardingSink(real) {
                    var written = 0L
                    override fun write(source: Buffer, byteCount: Long) {
                        super.write(source, byteCount)
                        written += byteCount
                        if (written >= chunk.size) firstChunkOnDisk.complete(Unit)
                    }
                }
            }
        }
        val body = ByteChannel(autoFlush = true)
        val client = HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "video/mp4")) })

        val server = launch(Dispatchers.Default) {
            body.writeFully(chunk)
            // Real time: the download runs on real threads, which the test scheduler cannot see.
            withTimeout(10_000) { firstChunkOnDisk.await() }
            body.writeFully(chunk)
            body.flushAndClose()
        }
        // Stall bound above the server's wait, so a buffering client fails on the server's
        // timeout rather than on its own.
        val result = DownloadEngine(client, fs, stallTimeoutMillis = 15_000, ioContext = Dispatchers.Default.limitedParallelism(1))
            .downloadFile(MemoryItem(id = "vid", url = "https://media.com/v.mp4?mid=vid", isGet = true, dateStr = null), "/output")
        server.join()

        assertTrue(server.isCompleted && !server.isCancelled, "the server gave up waiting: the body was buffered, not streamed")
        assertEquals("downloaded", result.status)
    }
}


private const val JPEG_BYTES = "jpeg-bytes"
