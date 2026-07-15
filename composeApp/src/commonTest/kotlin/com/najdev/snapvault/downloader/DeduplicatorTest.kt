package com.najdev.snapvault.downloader

import okio.FileSystem
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
    fun testDeduplication() {
        val fs = FileSystem.SYSTEM
        val tempDir = "build/test-dedupe-run_UUID".toPath()
        fs.createDirectories(tempDir)
        
        val file1 = tempDir / "file1.txt"
        val file2 = tempDir / "file2.txt"

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
    fun testKeepsEarliestDatedCopy() {
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
    fun testProtectedFilesAreNeverTouched() {
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
}
