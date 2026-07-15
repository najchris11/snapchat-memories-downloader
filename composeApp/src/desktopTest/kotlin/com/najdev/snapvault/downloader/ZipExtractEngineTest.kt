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

    @Test
    fun cleansStalePartFilesFromPreviousRun() {
        val stale = File(outDir, "2023-10-12_ABC-main.jpg.x1y2.part").apply { writeText("truncated") }
        val zip = createZip("export.zip", mapOf("memories/2023-10-12_ABC-main.jpg" to "bytes".toByteArray()))

        runExtract(zip, listOf(entry("2023-10-12_ABC-main.jpg")))

        assertTrue(!stale.exists(), "stale .part file from an interrupted run must be removed")
        assertEquals("bytes", File(outDir, "2023-10-12_ABC-main.jpg").readText())
    }
}
