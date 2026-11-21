package com.najdev.snapvault

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.najdev.snapvault.metadata.exportAndroidVideoOverlay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real Media3 export on the device. Fixtures (shared with the iOS exporter tests): `source.mp4`
 * is a 120×160 test pattern with AAC audio, `rotated.mp4` is the same stream with a 90° display
 * matrix, and `overlay.png` is 120×160 with its top 24 rows red (alpha 230), transparent below.
 */
@RunWith(AndroidJUnit4::class)
class AndroidVideoOverlayExporterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before fun setUp() { dir = File(context.cacheDir, "video-overlay-test-${System.nanoTime()}").apply { mkdirs() } }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun fixture(name: String) = File(dir, name).also { file ->
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("video-overlay/$name")) { "missing fixture $name" }
            .use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    private fun tracks(file: File): List<String> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        } finally { extractor.release() }
    }

    private fun frame(file: File): Bitmap {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            requireNotNull(retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) { "no frame" }
        } finally { retriever.release() }
    }

    private fun isOverlayRed(pixel: Int) = Color.red(pixel) > 180 && Color.green(pixel) < 90 && Color.blue(pixel) < 90

    private fun exportAndCheck(sourceName: String, displayWidth: Int, displayHeight: Int) = runBlocking {
        val source = fixture(sourceName)
        val overlay = fixture("overlay.png")
        val output = File(dir, "out.mp4")
        assertTrue("export must complete", exportAndroidVideoOverlay(context, source.path, overlay.path, output.path))
        assertTrue(source.exists() && overlay.exists())
        val mimes = tracks(output)
        assertEquals("one video and one audio track: $mimes", 1, mimes.count { it.startsWith("video/") })
        assertEquals("one video and one audio track: $mimes", 1, mimes.count { it.startsWith("audio/") })

        val exported = frame(output)
        val original = frame(source)
        assertEquals(displayWidth, exported.width)
        assertEquals(displayHeight, exported.height)
        // The overlay covers the top ~15% of the displayed frame. Across a row inside it, every
        // column where the source is not already red must be red after export (the columns where
        // the source is red are the negative control's exclusions, so the test pattern cannot
        // produce a pass). A row in the transparent part must look like the source.
        val y = displayHeight * 5 / 160
        val columns = (2 until displayWidth - 2 step 4).filter { !isOverlayRed(original.getPixel(it, y)) }
        assertTrue("negative control: enough non-red source columns at y=$y (${columns.size})", columns.size >= displayWidth / 8)
        val missing = columns.filter { !isOverlayRed(exported.getPixel(it, y)) }
        assertTrue("overlay must be burned in across y=$y; not red at x=$missing", missing.isEmpty())
        val below = displayHeight * 3 / 4
        val falseRed = (2 until displayWidth - 2 step 4).filter { isOverlayRed(exported.getPixel(it, below)) && !isOverlayRed(original.getPixel(it, below)) }
        assertTrue("the overlay is transparent at y=$below, but red appeared at x=$falseRed", falseRed.isEmpty())
    }

    @Test(timeout = 120_000)
    fun anOverlayIsBurnedIntoTheVideoAndAudioIsKept() = exportAndCheck("source.mp4", 120, 160)

    @Test(timeout = 120_000)
    fun aRotatedClipGetsTheOverlayAcrossItsDisplayedFrame() = exportAndCheck("rotated.mp4", 160, 120)

    @Test(timeout = 120_000)
    fun cancellingStopsTheEncoderBeforeReturning() = runBlocking {
        val source = fixture("source.mp4")
        val overlay = fixture("overlay.png")
        val output = File(dir, "cancelled.mp4")
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            exportAndroidVideoOverlay(context, source.path, overlay.path, output.path)
        }
        delay(50)
        job.cancel()
        runCatching { job.await() }
        assertTrue(job.isCancelled)
        // The exporter only returns once Transformer has stopped, so deleting the stage now is
        // final: nothing may recreate it afterwards.
        output.delete()
        delay(1_000)
        assertFalse("no late write after cancellation returned", output.exists())
        assertTrue(source.exists() && overlay.exists())
    }

    // The ZIP pipeline must actually hand videos to the exporter: the combiner tests use a fake
    // encoder and the cases above call the exporter directly, so only this case covers the
    // runner's wiring, the staged `combine-N-<name>.mp4` path and the real publish step.
    @Test(timeout = 120_000)
    fun theZipPipelineBurnsAVideoOverlayAndKeepsTheOriginals() = runBlocking {
        val previous = ContextHolder.context
        ContextHolder.context = context // MainActivity sets this in the app
        try {
            val main = File(dir, "2024-01-02_vid-main.mp4").also { f -> fixture("source.mp4").renameTo(f) }
            val overlay = File(dir, "2024-01-02_vid-overlay.png").also { f -> fixture("overlay.png").renameTo(f) }
            val results = mutableListOf<com.najdev.snapvault.downloader.CombineResult>()
            com.najdev.snapvault.downloader.AndroidZipPipelineRunner(com.najdev.snapvault.metadata.AndroidMediaProcessor())
                .combineAll(dir.path, deleteOriginals = true, workerCount = 1, onStart = {}, onMetaStart = {}, onMetaError = null) { results += it }
            val result = results.single()
            assertEquals("combined", result.status)
            assertFalse("a combined video must not claim its source's metadata", result.metadataCarried)
            val output = File(dir, "2024-01-02_vid.mp4")
            assertTrue(output.isFile)
            assertEquals(1, tracks(output).count { it.startsWith("video/") })
            assertTrue("video originals are kept even with deleteOriginals", main.exists() && overlay.exists())
            assertFalse(File(dir, ".snapvault-staging").exists())
        } finally {
            ContextHolder.context = previous
        }
    }

    // Characterisation (not a pass/fail claim about HDR support): records what Media3 does with
    // HLG/PQ sources on this device. A completed export must not silently lose HDR; see below.
    @Test(timeout = 180_000)
    fun hdrSourcesAreNotSilentlyDowngraded() = runBlocking {
        val observed = mutableListOf<String>()
        for (name in listOf("hdr-hlg.mp4", "hdr-pq.mp4")) {
            val source = fixture(name)
            val overlay = fixture("overlay.png")
            val output = File(dir, "out-$name")
            val completed = exportAndroidVideoOverlay(context, source.path, overlay.path, output.path)
            val format = if (completed && output.exists()) colour(output) else null
            observed += "$name completed=$completed source=${colour(source)} output=$format"
            if (completed) {
                assertEquals("$name: a completed export kept the HDR transfer (${observed.last()})", colour(source).second, format?.second)
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("hdr", observed.joinToString(" | ")) })
        println("HDR-ANDROID ${observed.joinToString(" | ")}")
    }

    private fun colour(file: File): Pair<String, Int?> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .first { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else null
            format.getString(MediaFormat.KEY_MIME).orEmpty() to transfer
        } finally { extractor.release() }
    }
}
