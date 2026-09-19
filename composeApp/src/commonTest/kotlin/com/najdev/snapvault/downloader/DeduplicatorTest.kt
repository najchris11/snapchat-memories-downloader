package com.najdev.snapvault.downloader

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeduplicatorTest {
    @Test
    fun testSha256Calculation() {
        val fs = FileSystem.SYSTEM
        val tempDir = "build/test-dedupe-sha".toPath()
        fs.createDirectories(tempDir)
        val tempFile = tempDir / "test.txt"
        fs.write(tempFile) {
            writeUtf8("Hello World")
        }

        val deduplicator = Deduplicator(fs)
        val hash = deduplicator.calculateSha256(tempFile)
        // SHA-256 of "Hello World"
        assertEquals("a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e", hash)

        fs.delete(tempFile)
        fs.delete(tempDir)
    }

    @Test
    fun testDeduplication() = runTest {
        val fs = FileSystem.SYSTEM
        val tempDir = "build/test-dedupe-run_UUID".toPath()
        fs.createDirectories(tempDir)

        val file1 = tempDir / "file1.jpg"
        val file2 = tempDir / "file2.jpg"

        fs.write(file1) { writeUtf8("same content") }
        fs.write(file2) { writeUtf8("same content") }

        val deduplicator = Deduplicator(fs)

        // Dry run test
        val dryResults = deduplicator.deduplicateFolder(tempDir, dryRun = true)
        assertEquals(1, dryResults.size)
        assertEquals(1, dryResults[0].deletedFiles.size)
        assertTrue(fs.exists(file1))
        assertTrue(fs.exists(file2))

        // Actual run test
        val actualResults = deduplicator.deduplicateFolder(tempDir, dryRun = false)
        assertEquals(1, actualResults.size)
        val deletedFile = actualResults[0].deletedFiles[0]
        val keptFile = actualResults[0].keptFile

        assertTrue(fs.exists(tempDir / keptFile))
        assertTrue(!fs.exists(tempDir / deletedFile))

        fs.delete(tempDir / keptFile)
        fs.delete(tempDir)
    }

    // Keep-selection must be deterministic: filenames start with YYYY-MM-DD, so the
    // lexicographically-first (earliest-dated) copy survives — not filesystem order.
    @Test
    fun testKeepsEarliestDatedCopy() = runTest {
        val fs = okio.fakefilesystem.FakeFileSystem()
        val dir = "/out".toPath()
        fs.createDirectories(dir)
        fs.write(dir / "2023-11-30_ZZZ.jpg") { writeUtf8("dupe-bytes") }
        fs.write(dir / "2021-05-01_AAA.jpg") { writeUtf8("dupe-bytes") }
        fs.write(dir / "2022-07-04_MMM.jpg") { writeUtf8("dupe-bytes") }

        val results = Deduplicator(fs).deduplicateFolder(dir, dryRun = false)

        assertEquals(1, results.size)
        assertEquals("2021-05-01_AAA.jpg", results[0].keptFile)
        assertEquals(
            listOf("2022-07-04_MMM.jpg", "2023-11-30_ZZZ.jpg"),
            results[0].deletedFiles.sorted()
        )
        assertTrue(fs.exists(dir / "2021-05-01_AAA.jpg"))
    }

    // Pipeline-managed files must never be deletion candidates, even with identical bytes.
    @Test
    fun testProtectedFilesAreNeverTouched() = runTest {
        val fs = okio.fakefilesystem.FakeFileSystem()
        val dir = "/out".toPath()
        fs.createDirectories(dir)
        fs.write(dir / "vault_index.json") { writeUtf8("same") }
        fs.write(dir / "photo.jpg") { writeUtf8("same") }
        fs.write(dir / "video.mp4.abc.part") { writeUtf8("same") }

        val results = Deduplicator(fs).deduplicateFolder(dir, dryRun = false)

        assertTrue(results.isEmpty(), "protected files must not form duplicate groups")
        assertTrue(fs.exists(dir / "vault_index.json"))
        assertTrue(fs.exists(dir / "photo.jpg"))
        assertTrue(fs.exists(dir / "video.mp4.abc.part"))
    }

    // Regression for BUG-17: a delete that throws (permission error, file lock, read-only
    // mount) must be reported as failed, not silently folded into deletedFiles as if it
    // had succeeded — and the file must still be on disk.
    @Test
    fun testFailedDeleteIsReportedSeparatelyFromDeleted() = runTest {
        val real = okio.fakefilesystem.FakeFileSystem()
        val dir = "/out".toPath()
        real.createDirectories(dir)
        real.write(dir / "2021-05-01_AAA.jpg") { writeUtf8("dupe-bytes") }
        real.write(dir / "2022-07-04_MMM.jpg") { writeUtf8("dupe-bytes") }

        val faulty = object : ForwardingFileSystem(real) {
            override fun delete(path: Path, mustExist: Boolean) {
                if (path.name == "2022-07-04_MMM.jpg") throw IOException("permission denied (simulated)")
                super.delete(path, mustExist)
            }
        }

        val results = Deduplicator(faulty).deduplicateFolder(dir, dryRun = false)

        assertEquals(1, results.size)
        assertEquals("2021-05-01_AAA.jpg", results[0].keptFile)
        assertTrue(results[0].deletedFiles.isEmpty())
        assertEquals(listOf("2022-07-04_MMM.jpg"), results[0].failedFiles)
        // The point of the fix: a failed delete must leave the file on disk, and the
        // result must say so rather than claiming it was deleted.
        assertTrue(real.exists(dir / "2022-07-04_MMM.jpg"))
    }

    // Dry run must never report a failure — it never attempts a delete in the first place.
    @Test
    fun testDryRunNeverReportsFailures() = runTest {
        val fs = okio.fakefilesystem.FakeFileSystem()
        val dir = "/out".toPath()
        fs.createDirectories(dir)
        fs.write(dir / "2021-05-01_AAA.jpg") { writeUtf8("dupe-bytes") }
        fs.write(dir / "2022-07-04_MMM.jpg") { writeUtf8("dupe-bytes") }

        val results = Deduplicator(fs).deduplicateFolder(dir, dryRun = true)

        assertEquals(1, results.size)
        assertTrue(results[0].failedFiles.isEmpty())
        assertEquals(listOf("2022-07-04_MMM.jpg"), results[0].deletedFiles)
        assertTrue(fs.exists(dir / "2022-07-04_MMM.jpg"))
    }

    // Regression for BUG-07: deduplicateFolder used to be a plain blocking function with no
    // suspension point at all, so cancellation could never interrupt it. Cancelling the
    // coroutine's own job before it does any real work must now stop it before it deletes
    // anything, proving the ensureActive() checks are actually reachable and effective.
    @Test
    fun testCancellationStopsBeforeAnyDeletion() = runTest {
        val fs = okio.fakefilesystem.FakeFileSystem()
        val dir = "/out".toPath()
        fs.createDirectories(dir)
        fs.write(dir / "2021-05-01_AAA.jpg") { writeUtf8("dupe-bytes") }
        fs.write(dir / "2022-07-04_MMM.jpg") { writeUtf8("dupe-bytes") }

        val job = launch {
            cancel()
            Deduplicator(fs).deduplicateFolder(dir, dryRun = false)
        }
        job.join()

        assertTrue(job.isCancelled)
        assertTrue(fs.exists(dir / "2021-05-01_AAA.jpg"))
        assertTrue(fs.exists(dir / "2022-07-04_MMM.jpg"))
    }

    // ── D04: what dedupe may touch, and which copy it keeps ─────────────────

    private fun fakeDir(vararg files: Pair<String, String>): Pair<okio.fakefilesystem.FakeFileSystem, Path> {
        val fs = okio.fakefilesystem.FakeFileSystem()
        val dir = "/out".toPath()
        fs.createDirectories(dir)
        files.forEach { (name, text) -> fs.write(dir / name) { writeUtf8(text) } }
        return fs to dir
    }

    // D04: the kept copy was always the lexicographically first name. Byte equality protects
    // the pixels, not the user's choice: favorite the later copy and dedupe deleted it, taking
    // the favorite with it — the one thing in the library no re-run can rebuild.
    @Test
    fun aFavoritedCopyIsNeverTheOneDeleted() = runTest {
        val (fs, dir) = fakeDir("2021-05-01_AAA.jpg" to "same", "2023-11-30_ZZZ.jpg" to "same")

        val results = Deduplicator(fs).deduplicateFolder(dir, dryRun = false, favorites = setOf("2023-11-30_ZZZ.jpg"))

        assertEquals("2023-11-30_ZZZ.jpg", results.single().keptFile)
        assertEquals(listOf("2021-05-01_AAA.jpg"), results.single().deletedFiles)
        assertTrue(fs.exists(dir / "2023-11-30_ZZZ.jpg"))
    }

    // A user who favorited two copies meant both. Dedupe removes only what nobody asked to keep.
    @Test
    fun everyFavoritedCopyIsKept() = runTest {
        val (fs, dir) = fakeDir(
            "2021-05-01_AAA.jpg" to "same",
            "2022-07-04_MMM.jpg" to "same",
            "2023-11-30_ZZZ.jpg" to "same",
        )

        val results = Deduplicator(fs)
            .deduplicateFolder(dir, dryRun = false, favorites = setOf("2022-07-04_MMM.jpg", "2023-11-30_ZZZ.jpg"))

        assertEquals(listOf("2021-05-01_AAA.jpg"), results.single().deletedFiles)
        assertTrue(fs.exists(dir / "2022-07-04_MMM.jpg"))
        assertTrue(fs.exists(dir / "2023-11-30_ZZZ.jpg"))
    }

    // Nothing to delete when every copy is favorited — and no result claiming a dedupe happened.
    @Test
    fun aGroupOfOnlyFavoritesIsLeftAlone() = runTest {
        val (fs, dir) = fakeDir("2021-05-01_AAA.jpg" to "same", "2023-11-30_ZZZ.jpg" to "same")

        val results = Deduplicator(fs)
            .deduplicateFolder(dir, dryRun = false, favorites = setOf("2021-05-01_AAA.jpg", "2023-11-30_ZZZ.jpg"))

        assertTrue(results.isEmpty(), "was: $results")
        assertTrue(fs.exists(dir / "2021-05-01_AAA.jpg") && fs.exists(dir / "2023-11-30_ZZZ.jpg"))
    }

    // D04: every regular file in the output folder was a candidate. Choose Documents or
    // Downloads as the destination and two identical PDFs, ZIPs or notes became "duplicates"
    // for deletion. Dedupe is for the memories SnapVault manages; anything the Library would
    // not show is not its business.
    @Test
    fun onlyMediaTheLibraryRecognisesIsConsidered() = runTest {
        val (fs, dir) = fakeDir(
            "notes.txt" to "same text",
            "notes (copy).txt" to "same text",
            "invoice.pdf" to "same pdf",
            "invoice-2.pdf" to "same pdf",
            "backup.zip" to "same zip",
            "backup-old.zip" to "same zip",
            "2021-05-01_AAA.jpg" to "same photo",
            "2023-11-30_ZZZ.jpg" to "same photo",
        )

        val results = Deduplicator(fs).deduplicateFolder(dir, dryRun = false)

        assertEquals(listOf("2023-11-30_ZZZ.jpg"), results.flatMap { it.deletedFiles })
        listOf("notes.txt", "notes (copy).txt", "invoice.pdf", "invoice-2.pdf", "backup.zip", "backup-old.zip")
            .forEach { assertTrue(fs.exists(dir / it), "$it is not a memory and must not be touched") }
    }
}
