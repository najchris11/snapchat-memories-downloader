package com.najdev.snapvault.downloader

import com.najdev.snapvault.BinaryExtractor
import com.najdev.snapvault.metadata.DesktopMediaProcessor
import com.najdev.snapvault.metadata.MediaProcessor
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverlayCombinerTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = File.createTempFile("combine-test", "").apply { delete(); mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    // Minimal MediaProcessor: video combine behavior is injectable per test.
    private class FakeProcessor(
        val onVideoCombine: (outputPath: String) -> Boolean = { true },
    ) : MediaProcessor {
        override fun checkExifTool() = false
        override fun checkFFmpeg() = false
        override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = false
        override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = false
        override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) =
            onVideoCombine(outputPath)
    }

    private fun writePng(name: String, w: Int = 4, h: Int = 4): File {
        val f = File(dir, name)
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), "PNG", f)
        return f
    }

    private fun combineAll(processor: MediaProcessor, deleteOriginals: Boolean = true): List<CombineResult> {
        val results = mutableListOf<CombineResult>()
        runBlocking {
            OverlayCombiner(processor).combineAll(
                dir.absolutePath,
                deleteOriginals = deleteOriginals,
                workerCount = 2,
            ) { results.add(it) }
        }
        return results
    }

    // ── findPairs ────────────────────────────────────────────────────────────

    @Test
    fun findPairsMatchesByStemAndIgnoresUnpaired() {
        writePng("2023-10-12_AAA-main.png")
        writePng("2023-10-12_AAA-overlay.png")
        writePng("2023-10-12_BBB-main.png") // no overlay
        writePng("2024-01-01_AAA-overlay.png") // same UUID, different date, no main

        val pairs = OverlayCombiner(FakeProcessor()).findPairs(dir.absolutePath)

        assertEquals(1, pairs.size)
        assertEquals("2023-10-12_AAA-main.png", pairs[0].mainFile.name)
        assertEquals("2023-10-12_AAA-overlay.png", pairs[0].overlayFile.name)
        assertEquals("2023-10-12_AAA.png", pairs[0].outputFile.name)
    }

    @Test
    fun findPairsMapsNonWritableImageFormatsToJpgOutput() {
        // HEIC/WebP can't be written by ImageIO; the output must be planned as .jpg upfront.
        File(dir, "2023-10-12_CCC-main.heic").writeBytes(byteArrayOf(1))
        writePng("2023-10-12_CCC-overlay.png")

        val pairs = OverlayCombiner(FakeProcessor()).findPairs(dir.absolutePath)

        assertEquals(1, pairs.size)
        assertEquals("2023-10-12_CCC.jpg", pairs[0].outputFile.name)
    }

    @Test
    fun findPairsClassifiesVideoExtensions() {
        File(dir, "2023-10-12_VVV-main.mp4").writeBytes(byteArrayOf(1))
        writePng("2023-10-12_VVV-overlay.png")

        val pairs = OverlayCombiner(FakeProcessor()).findPairs(dir.absolutePath)
        assertTrue(pairs.single().isVideo)
    }

    // ── processPair safety (deletion only after verified output) ────────────

    @Test
    fun imagePairCombinesAndDeletesOriginals() {
        val main = writePng("2023-10-12_AAA-main.png", 8, 8)
        val overlay = writePng("2023-10-12_AAA-overlay.png", 4, 4)

        val results = combineAll(FakeProcessor())

        assertEquals(listOf("combined"), results.map { it.status })
        assertTrue(File(dir, "2023-10-12_AAA.png").length() > 0, "combined output must exist")
        assertTrue(!main.exists() && !overlay.exists(), "originals must be deleted after success")
    }

    @Test
    fun originalsSurviveWhenOutputWasNotWritten() {
        // A processor that claims success but writes nothing — the combiner must refuse
        // to delete the originals.
        val main = File(dir, "2023-10-12_VVV-main.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val overlay = writePng("2023-10-12_VVV-overlay.png")

        val results = combineAll(FakeProcessor(onVideoCombine = { true }))

        assertTrue(results.single().status.startsWith("error: output missing"))
        assertTrue(main.exists() && overlay.exists(), "originals must survive a phantom combine")
    }

    @Test
    fun originalsSurviveWhenVideoCombineFails() {
        val main = File(dir, "2023-10-12_VVV-main.mp4").apply { writeBytes(byteArrayOf(1)) }
        val overlay = writePng("2023-10-12_VVV-overlay.png")

        val results = combineAll(FakeProcessor(onVideoCombine = { false }))

        assertTrue(results.single().status.startsWith("error"))
        assertTrue(main.exists() && overlay.exists())
    }

    @Test
    fun deleteOriginalsFalseKeepsEverything() {
        val main = writePng("2023-10-12_AAA-main.png", 8, 8)
        val overlay = writePng("2023-10-12_AAA-overlay.png")

        combineAll(FakeProcessor(), deleteOriginals = false)

        assertTrue(main.exists() && overlay.exists())
        assertTrue(File(dir, "2023-10-12_AAA.png").exists())
    }

    // ── BUG-01 regression: combine must not clobber a precise capture time ──

    // Real exiftool is required to prove the tag round-trip; skip gracefully on a machine
    // without one (fresh checkout — see BUG-11) rather than failing for an unrelated reason.
    @Test
    fun combinedOutputPreservesPreciseTimeInsteadOfMidnight() {
        if (BinaryExtractor.checkCommand("exiftool") == null) return

        val processor = DesktopMediaProcessor()
        val main = writePng("2024-03-15_AAA-main.png", 8, 8)
        writePng("2024-03-15_AAA-overlay.png", 4, 4)

        // Simulates what the metadata phase writes when the experimental matcher finds a
        // unique capture-time match: a precise, non-midnight timestamp on the -main file.
        assertTrue(
            processor.writeDateMetadata(main.absolutePath, "2024-03-15 14:23:45"),
            "setup: writing the precise source timestamp must succeed",
        )

        val results = mutableListOf<CombineResult>()
        runBlocking {
            OverlayCombiner(processor).combineAll(dir.absolutePath, deleteOriginals = true, workerCount = 2) {
                results.add(it)
            }
        }

        assertEquals(listOf("combined"), results.map { it.status })
        val combined = File(dir, "2024-03-15_AAA.png")
        assertTrue(combined.exists())

        val exiftoolPath = BinaryExtractor.checkCommand("exiftool")!!
        val proc = ProcessBuilder(exiftoolPath, "-s3", "-DateTimeOriginal", combined.absolutePath)
            .redirectErrorStream(true)
            .start()
        val tag = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()

        assertTrue(tag.isNotEmpty(), "combined output must carry a DateTimeOriginal tag")
        assertTrue(
            tag.contains("14:23:45"),
            "BUG-01 regression: the precise time-of-day must survive the combine phase, got: $tag",
        )
    }
}
