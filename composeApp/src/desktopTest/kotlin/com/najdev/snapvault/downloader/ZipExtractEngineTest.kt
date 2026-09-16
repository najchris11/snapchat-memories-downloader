package com.najdev.snapvault.downloader

import com.najdev.snapvault.parser.HtmlMemoryEntry
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZipExtractEngineTest {

    private lateinit var workDir: File
    private lateinit var outDir: File

    @BeforeTest
    fun setUp() {
        workDir = File.createTempFile("zip-extract-test", "").apply {
            delete()
            mkdirs()
        }
        outDir = File(workDir, "out").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun createZip(name: String, entries: Map<String, ByteArray>): File {
        val zipFile = File(workDir, name)
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            for ((entryName, bytes) in entries) {
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return zipFile
    }

    /** A legacy downloaded memory archive, written into the output directory as the download phase leaves it. */
    private fun legacyArchive(name: String, entries: Map<String, String>): File {
        val archive = File(outDir, name)
        ZipOutputStream(archive.outputStream()).use { zos ->
            for ((entryName, text) in entries) {
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }
        return archive
    }

    private fun entry(fileName: String, overlayFileName: String? = null) = HtmlMemoryEntry(
        fileName = fileName,
        uuid = fileName.substringAfter('_').substringBefore('-').substringBefore('.'),
        date = fileName.substringBefore('_'),
        isVideo = false,
        hasOverlay = overlayFileName != null,
        overlayFileName = overlayFileName,
    )

    private fun runExtract(zip: File, entries: List<HtmlMemoryEntry>): List<ExtractResult> {
        val results = mutableListOf<ExtractResult>()
        runBlocking {
            ZipExtractEngine().extractAll(
                mapOf(zip.absolutePath to entries),
                outDir.absolutePath,
                workerCount = 2,
            ) { results.add(it) }
        }
        return results
    }

    @Test
    fun extractsMainAndOverlayBytes() {
        val mainBytes = "main-content".toByteArray()
        val overlayBytes = "overlay-content".toByteArray()
        val zip = createZip(
            "export.zip",
            mapOf(
                "memories/2023-10-12_ABC-main.jpg" to mainBytes,
                "memories/2023-10-12_ABC-overlay.png" to overlayBytes,
            )
        )

        val results = runExtract(zip, listOf(entry("2023-10-12_ABC-main.jpg", "2023-10-12_ABC-overlay.png")))

        assertEquals(2, results.size)
        assertTrue(results.all { it.error == null && !it.skipped })
        assertEquals(mainBytes.toList(), File(outDir, "2023-10-12_ABC-main.jpg").readBytes().toList())
        assertEquals(overlayBytes.toList(), File(outDir, "2023-10-12_ABC-overlay.png").readBytes().toList())
    }

    @Test
    fun skipsExistingFileWithoutRewriting() {
        val zip = createZip("export.zip", mapOf("memories/2023-10-12_ABC-main.jpg" to "new".toByteArray()))
        val preExisting = File(outDir, "2023-10-12_ABC-main.jpg").apply { writeText("original") }

        val results = runExtract(zip, listOf(entry("2023-10-12_ABC-main.jpg")))

        assertEquals(1, results.size)
        assertTrue(results[0].skipped)
        assertEquals("original", preExisting.readText(), "existing files must not be overwritten")
    }

    @Test
    fun reportsMissingEntryAsError() {
        val zip = createZip("export.zip", mapOf("memories/other.jpg" to "x".toByteArray()))

        val results = runExtract(zip, listOf(entry("2023-10-12_MISSING-main.jpg")))

        assertEquals(1, results.size)
        assertTrue(results[0].error?.contains("entry not found") == true)
    }

    // Regression (B5): no temp file may survive a successful run, and truncated files
    // from a previous crash (.part) must not be mistaken for extracted output.
    @Test
    fun leavesNoPartFilesBehind() {
        val zip = createZip("export.zip", mapOf("memories/2023-10-12_ABC-main.jpg" to "bytes".toByteArray()))

        runExtract(zip, listOf(entry("2023-10-12_ABC-main.jpg")))

        val leftovers = outDir.listFiles()!!.filter { it.name.endsWith(".part") }
        assertTrue(leftovers.isEmpty(), "no .part temp files may remain, found: $leftovers")
    }

    // ── extractDownloadedArchives (legacy pipeline) ──────────────────────────

    @Test
    fun extractsLegacyArchiveAsMainOverlayPairAndDeletesIt() {
        val archive = legacyArchive(
            "20231012_153000_abc.zip",
            linkedMapOf(
                "media~xyz.mp4" to "video-bytes",
                "overlay~xyz.png" to "overlay-bytes",
                "thumbnail~xyz.jpg" to "thumb",
            ),
        )

        val warnings = mutableListOf<String>()
        val extracted = runBlocking {
            ZipExtractEngine().extractDownloadedArchives(
                outDir.absolutePath, listOf(archive.absolutePath),
            ) { warnings.add(it) }
        }

        assertEquals(2, extracted.size, "media + overlay extracted, thumbnail skipped; warnings: $warnings")
        assertEquals("video-bytes", File(outDir, "20231012_153000_abc-main.mp4").readText())
        assertEquals("overlay-bytes", File(outDir, "20231012_153000_abc-overlay.png").readText())
        assertTrue(!archive.exists(), "archive must be deleted after full extraction")
        assertTrue(warnings.isEmpty(), "no warnings expected: $warnings")
    }

    // Regression (D01): the archive basename is the only thing that distinguishes an
    // extracted file, so two same-extension entries both flatten to "<base>-main.jpg".
    // The second one used to come back "skipped" — indistinguishable from "already
    // extracted on a previous run" — leaving allOk true and deleting the archive with
    // only the first photo on disk. The second photo existed nowhere else.
    @Test
    fun archiveWhoseEntriesCollideIsKeptIntact() {
        val archive = legacyArchive(
            "20231012_153000_abc.zip",
            linkedMapOf("one.jpg" to "first-photo", "two.jpg" to "second-photo"),
        )
        val before = archive.readBytes()

        val warnings = mutableListOf<String>()
        val extracted = runBlocking {
            ZipExtractEngine().extractDownloadedArchives(
                outDir.absolutePath, listOf(archive.absolutePath),
            ) { warnings.add(it) }
        }

        assertTrue(archive.exists(), "colliding archive must be kept; it holds the only copy of both photos")
        assertEquals(before.toList(), archive.readBytes().toList(), "kept archive must be byte-identical")
        assertTrue(extracted.isEmpty(), "nothing may be extracted from an archive that cannot be flattened safely")
        assertTrue(warnings.any { "one.jpg" in it || "two.jpg" in it }, "collision must be warned: $warnings")
    }

    // Regression (D01): every entry was a thumbnail, so the extraction loop skipped them
    // all, allOk stayed true, and the archive was deleted having produced no output.
    @Test
    fun thumbnailOnlyArchiveIsKeptIntact() {
        val archive = legacyArchive("20231012_153000_abc.zip", linkedMapOf("thumbnail~xyz.jpg" to "only-copy"))

        val warnings = mutableListOf<String>()
        val extracted = runBlocking {
            ZipExtractEngine().extractDownloadedArchives(
                outDir.absolutePath, listOf(archive.absolutePath),
            ) { warnings.add(it) }
        }

        assertTrue(archive.exists(), "archive that produced no output must never be deleted")
        assertTrue(extracted.isEmpty())
        assertTrue(warnings.isNotEmpty(), "silently deleting it was the bug; say why it was kept")
    }

    // Regression (D01): extraction used to enumerate every *.zip in the output directory.
    // Pointing the output at Downloads (or at the export folder itself) put unrelated
    // archives through the flatten-and-delete path.
    @Test
    fun archivesNotOwnedByThisRunAreNeverTouched() {
        val mine = legacyArchive("20231012_153000_abc.zip", linkedMapOf("media~xyz.mp4" to "video-bytes"))
        val theirs = legacyArchive("tax-returns-2023.zip", linkedMapOf("form.pdf" to "irreplaceable"))
        val theirsBefore = theirs.readBytes()

        val warnings = mutableListOf<String>()
        runBlocking {
            ZipExtractEngine().extractDownloadedArchives(
                outDir.absolutePath, listOf(mine.absolutePath),
            ) { warnings.add(it) }
        }

        assertTrue(theirs.exists(), "an archive this run did not download must not be read or deleted")
        assertEquals(theirsBefore.toList(), theirs.readBytes().toList())
        assertTrue(!File(outDir, "tax-returns-2023-main.pdf").exists(), "unrelated archive must not be flattened")
        assertTrue(!mine.exists(), "the run's own fully-extracted archive is still cleaned up")
    }

    @Test
    fun corruptArchiveIsKeptAndWarned() {
        val bogus = File(outDir, "20231012_153000_bad.zip").apply { writeText("not a zip") }

        val warnings = mutableListOf<String>()
        val extracted = runBlocking {
            ZipExtractEngine().extractDownloadedArchives(
                outDir.absolutePath, listOf(bogus.absolutePath),
            ) { warnings.add(it) }
        }

        assertTrue(extracted.isEmpty())
        assertTrue(bogus.exists(), "unreadable archive must be kept for retry")
        assertTrue(warnings.isNotEmpty())
    }

    @Test
    fun cleansStalePartFilesFromPreviousRun() {
        val stale = File(outDir, "2023-10-12_ABC-main.jpg.x1y2.part").apply { writeText("truncated") }
        val zip = createZip("export.zip", mapOf("memories/2023-10-12_ABC-main.jpg" to "bytes".toByteArray()))

        runExtract(zip, listOf(entry("2023-10-12_ABC-main.jpg")))

        assertTrue(!stale.exists(), "stale .part file from an interrupted run must be removed")
        assertEquals("bytes", File(outDir, "2023-10-12_ABC-main.jpg").readText())
    }

    @Test
    fun concurrentExtractionOfSameFileIsSafe() {
        // Two zips both containing the same entry name. ZipExtractEngine should handle
        // the race by one worker winning the move and the other deleting its temp file.
        val bytes1 = "v1".toByteArray()
        val bytes2 = "v2".toByteArray()
        val zip1 = createZip("export1.zip", mapOf("memories/shared.jpg" to bytes1))
        val zip2 = createZip("export2.zip", mapOf("memories/shared.jpg" to bytes2))

        val results = mutableListOf<ExtractResult>()
        runBlocking {
            ZipExtractEngine().extractAll(
                mapOf(
                    zip1.absolutePath to listOf(entry("shared.jpg")),
                    zip2.absolutePath to listOf(entry("shared.jpg")),
                ),
                outDir.absolutePath,
                workerCount = 4,
            ) { results.add(it) }
        }

        assertEquals(2, results.size)
        val oneSkipped = results.any { it.skipped }
        val oneOk = results.any { !it.skipped && it.error == null }
        assertTrue(oneOk, "at least one must succeed")
        assertTrue(oneSkipped, "the second one must be skipped because the file was moved into place")
        
        // Final content should be either v1 or v2 (atomic move won)
        val finalContent = File(outDir, "shared.jpg").readText()
        assertTrue(finalContent == "v1" || finalContent == "v2")
    }
}
