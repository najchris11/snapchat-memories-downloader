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
}
