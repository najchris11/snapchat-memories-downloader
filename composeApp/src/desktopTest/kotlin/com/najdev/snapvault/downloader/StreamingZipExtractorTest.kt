package com.najdev.snapvault.downloader

import com.najdev.snapvault.parser.HtmlMemoryEntry
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingZipExtractorTest {
    private fun entry(name: String) = HtmlMemoryEntry(name, "AAA", "2024-01-01", false, false, null)
    @Test
    fun mediaStreamsIntoTheLibraryWithoutCopyingTheArchiveOrOverwritingExistingFiles() = runBlocking {
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                for ((name, value) in listOf("fresh.jpg" to "fresh", "kept.jpg" to "replacement")) {
                    zip.putNextEntry(ZipEntry("memories/$name")); zip.write(value.toByteArray()); zip.closeEntry()
                }
            }
        }.toByteArray()
        val folder = kotlin.io.path.createTempDirectory("stream-extract").toFile()
        try {
            File(folder, "kept.jpg").writeText("original")
            val results = mutableListOf<ExtractResult>()
            StreamingZipExtractor { ByteArrayInputStream(bytes) }.extract("content://export/1", listOf(entry("fresh.jpg"), entry("kept.jpg")), folder.path, results::add)
            assertEquals("fresh", File(folder, "fresh.jpg").readText())
            assertEquals("original", File(folder, "kept.jpg").readText())
            assertEquals(2, results.size)
            assertTrue(results.single { it.fileName == "kept.jpg" }.skipped)
            assertEquals(setOf("fresh.jpg", "kept.jpg"), folder.list()!!.toSet())
        } finally { folder.deleteRecursively() }
    }
    @Test
    fun unwritableDestinationsReportAFailureForEveryRequestedFile() = runBlocking {
        val bytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip -> zip.putNextEntry(ZipEntry("memories/fresh.jpg")); zip.write(byteArrayOf(1)); zip.closeEntry() }
        }.toByteArray()
        val notDirectory = File.createTempFile("not-a-directory", ".txt").apply { writeText("kept") }
        try {
            val results = mutableListOf<ExtractResult>()
            StreamingZipExtractor { ByteArrayInputStream(bytes) }.extract("content://export/1", listOf(entry("fresh.jpg")), notDirectory.path, results::add)
            assertEquals(1, results.size)
            assertTrue(results.single().error != null)
            assertEquals("kept", notDirectory.readText())
        } finally { notDirectory.delete() }
    }

    private fun zipOf(vararg files: Pair<String, String>) = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            for ((name, value) in files) { zip.putNextEntry(ZipEntry("memories/$name")); zip.write(value.toByteArray()); zip.closeEntry() }
        }
    }.toByteArray()

    // Regression: entries were staged as `.snapvault-stream-*.part` directly in the library
    // folder. A process killed mid-entry (Android may kill a backgrounded import at any time)
    // left that file there for good, because nothing ever looked for it again. Staging now
    // goes through the shared `.snapvault-staging` directory, which the next run clears.
    @Test
    fun inProgressEntriesAreStagedOutsideTheLibraryFolder() = runBlocking {
        val folder = kotlin.io.path.createTempDirectory("stream-staging").toFile()
        try {
            // Incompressible and read in small chunks, so reads continue while the entry is staged.
            val payload = kotlin.random.Random(1).nextBytes(256 * 1024)
            val bytes = ByteArrayOutputStream().apply {
                ZipOutputStream(this).use { zip -> zip.putNextEntry(ZipEntry("memories/big.jpg")); zip.write(payload); zip.closeEntry() }
            }.toByteArray()
            val seen = mutableListOf<Pair<List<String>, List<String>>>()
            val probing = object : java.io.FilterInputStream(ByteArrayInputStream(bytes)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val staging = File(folder, ".snapvault-staging")
                    seen += folder.list()!!.filter { it.endsWith(".part") } to (staging.list()?.toList() ?: emptyList())
                    return super.read(b, off, minOf(len, 1024))
                }
            }
            StreamingZipExtractor { probing }.extract("content://export/1", listOf(entry("big.jpg")), folder.path) {}
            assertTrue(seen.all { it.first.isEmpty() }, "no .part may appear in the library folder: $seen")
            assertTrue(seen.any { it.second.any { name -> name.endsWith(".part") } }, "the entry must be staged in .snapvault-staging")
            assertEquals(setOf("big.jpg"), folder.list()!!.toSet(), "staging is removed once everything is published")
            assertTrue(payload.contentEquals(File(folder, "big.jpg").readBytes()))
        } finally { folder.deleteRecursively() }
    }

    @Test
    fun aStagedFileLeftByAKilledRunIsRemovedByTheNextRun() = runBlocking {
        val folder = kotlin.io.path.createTempDirectory("stream-stale").toFile()
        try {
            val stale = File(folder, ".snapvault-staging/.snapvault-stream-123.part").apply { parentFile.mkdirs(); writeText("truncated") }
            val bytes = zipOf("fresh.jpg" to "fresh")
            StreamingZipExtractor { ByteArrayInputStream(bytes) }.extract("content://export/1", listOf(entry("fresh.jpg")), folder.path) {}
            assertTrue(!stale.exists(), "the killed run's staged file must be removed")
            assertEquals(setOf("fresh.jpg"), folder.list()!!.toSet())
            assertEquals("fresh", File(folder, "fresh.jpg").readText())
        } finally { folder.deleteRecursively() }
    }
}
