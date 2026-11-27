package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android used to skip every video/overlay pair. The shared combiner now accepts a video
 * encoder (Media3 on Android) and must give videos the same staging and no-overwrite safety
 * as images, while never deleting a video's originals: unlike the image path, nothing proves
 * the encoded video carried its metadata, so the originals are the only verified copy.
 */
class StagedVideoOverlayCombinerTest {
    private lateinit var dir: File
    private val dateBatches = mutableListOf<List<String>>()

    private val processor = object : MediaProcessor {
        override fun checkExifTool() = false
        override fun checkFFmpeg() = false
        override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = false
        override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = false
        override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = false
        override fun writeDateMetadataBatch(filePaths: List<String>, dateOnly: String, onError: ((String) -> Unit)?): Int {
            dateBatches += filePaths
            return filePaths.size
        }
    }

    @BeforeTest fun setUp() { dir = kotlin.io.path.createTempDirectory("video-combine").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun pair(stem: String, ext: String = "mp4"): Pair<File, File> =
        File(dir, "$stem-main.$ext").apply { writeText("video") } to File(dir, "$stem-overlay.png").apply { writeText("png") }

    private fun run(encoder: (suspend (String, String, String) -> Boolean)?, deleteOriginals: Boolean = true): List<CombineResult> {
        val results = mutableListOf<CombineResult>()
        runBlocking {
            StagedImageOverlayCombiner(processor, hasCaptureTimestamp = { false }, videoEncoder = encoder)
                .combineAll(dir.absolutePath, deleteOriginals, onProgress = { results += it })
        }
        return results
    }

    @Test
    fun withoutAnEncoderVideosAreStillSkipped() {
        val (main, overlay) = pair("2024-01-02_a")
        val results = run(encoder = null)
        assertTrue(results.single().status.startsWith("skipped: video"))
        assertTrue(main.exists() && overlay.exists())
        assertFalse(File(dir, "2024-01-02_a.mp4").exists())
    }

    @Test
    fun anEncodedVideoIsStagedPublishedAndKeepsItsOriginals() {
        val (main, overlay) = pair("2024-01-02_a")
        var stagedPath = ""
        val results = run(encoder = { m, o, out ->
            assertEquals(main.absolutePath, m); assertEquals(overlay.absolutePath, o)
            stagedPath = out
            File(out).writeText("encoded"); true
        })
        assertTrue(STAGING_DIR_NAME in stagedPath, "the encoder must write into staging, got $stagedPath")
        val result = results.single()
        assertEquals("combined", result.status)
        assertEquals("encoded", File(dir, "2024-01-02_a.mp4").readText())
        assertFalse(result.metadataCarried, "an encoded video must not claim its source's metadata")
        assertTrue(main.exists() && overlay.exists(), "video originals are never deleted, even with deleteOriginals")
        assertTrue(dateBatches.isEmpty(), "ExifInterface cannot write MP4; videos are excluded from the date fallback")
        assertFalse(File(dir, STAGING_DIR_NAME).exists())
    }

    @Test
    fun aMovPairIsSkippedBecauseTheEncoderOnlyWritesMp4() {
        pair("2024-01-02_m", ext = "mov")
        var called = false
        val results = run(encoder = { _, _, out -> called = true; File(out).writeText("x"); true })
        assertFalse(called)
        assertTrue(results.single().status.startsWith("skipped: video"))
    }

    @Test
    fun aFailingOrThrowingEncoderLeavesNothingBehind() {
        val (main, overlay) = pair("2024-01-02_f")
        val (main2, overlay2) = pair("2024-01-02_g")
        val results = run(encoder = { m, _, out ->
            File(out).writeText("partial")
            if (m.contains("_f")) false else error("codec died")
        })
        assertEquals(listOf("error: could not combine the overlay", "error: could not combine the overlay"), results.map { it.status })
        assertFalse(File(dir, "2024-01-02_f.mp4").exists())
        assertFalse(File(dir, "2024-01-02_g.mp4").exists())
        assertTrue(main.exists() && overlay.exists() && main2.exists() && overlay2.exists())
        assertFalse(File(dir, STAGING_DIR_NAME).exists(), "no staged leftovers")
    }

    @Test
    fun anExistingOutputIsNeitherEncodedNorTouched() {
        val (main, _) = pair("2024-01-02_e")
        val existing = File(dir, "2024-01-02_e.mp4").apply { writeText("user edit") }
        var called = false
        val results = run(encoder = { _, _, _ -> called = true; true })
        assertFalse(called)
        assertEquals("skipped: output already exists", results.single().status)
        assertEquals("user edit", existing.readText())
        assertTrue(main.exists())
    }

    @Test
    fun aDestinationCreatedDuringEncodingIsNotOverwritten() {
        val (main, overlay) = pair("2024-01-02_r")
        val results = run(encoder = { _, _, out ->
            File(dir, "2024-01-02_r.mp4").writeText("someone else")
            File(out).writeText("encoded"); true
        })
        assertEquals("skipped: output already exists", results.single().status)
        assertEquals("someone else", File(dir, "2024-01-02_r.mp4").readText())
        assertTrue(main.exists() && overlay.exists())
    }

    @Test
    fun cancellingDuringEncodingPropagatesAndLeavesNoOutput() = runBlocking {
        pair("2024-01-02_c")
        val started = CompletableDeferred<Unit>()
        val job = async {
            StagedImageOverlayCombiner(processor, videoEncoder = { _, _, out ->
                File(out).writeText("partial")
                started.complete(Unit)
                awaitCancellation()
            }).combineAll(dir.absolutePath, true, onProgress = {})
        }
        // Bounded so a combiner that never calls the encoder fails instead of hanging the suite.
        kotlinx.coroutines.withTimeout(10_000) { started.await() }
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertFalse(File(dir, "2024-01-02_c.mp4").exists())
        assertFalse(File(dir, STAGING_DIR_NAME).exists(), "the partial staged video must be removed")
    }
}
