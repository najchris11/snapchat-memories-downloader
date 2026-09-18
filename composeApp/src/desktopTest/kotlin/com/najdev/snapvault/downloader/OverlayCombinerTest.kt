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

    private fun combineAll(
        processor: MediaProcessor,
        deleteOriginals: Boolean = true,
        // Default mirrors a machine where exiftool is present and the copy works, so the
        // deletion tests below do not silently depend on what is installed.
        copyMetadata: (String, String) -> MetadataCopy = { _, _ -> MetadataCopy.Copied },
    ): List<CombineResult> {
        val results = mutableListOf<CombineResult>()
        runBlocking {
            OverlayCombiner(processor, copyMetadata).combineAll(
                dir.absolutePath,
                deleteOriginals = deleteOriginals,
                workerCount = 2,
            ) { results.add(it) }
        }
        return results
    }

    @Test
    fun reviewVideoWithoutMetadataToolMustKeepOriginals() {
        val main = File(dir, "2023-10-12_VVV-main.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val overlay = writePng("2023-10-12_VVV-overlay.png")
        val results = combineAll(FakeProcessor(onVideoCombine = { output ->
            File(output).writeBytes(byteArrayOf(4, 5, 6)); true
        }), copyMetadata = { _, _ -> MetadataCopy.Unavailable })
        assertTrue(main.exists() && overlay.exists(), "Deleted video originals with metadata unavailable; status=${results.single().status}")
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

    // Regression (D02): ImageIO wrote straight to the final path and ffmpeg ran with -y,
    // so a re-import silently overwrote a combined file the user had already edited — and
    // the originals were deleted afterwards, leaving no way back.
    @Test
    fun existingCombinedOutputIsNeverOverwritten() {
        val main = writePng("2023-10-12_AAA-main.png", 8, 8)
        val overlay = writePng("2023-10-12_AAA-overlay.png", 4, 4)
        val edited = File(dir, "2023-10-12_AAA.png").apply { writeText("the user's edited combined image") }
        val before = edited.readBytes()

        val results = combineAll(FakeProcessor())

        assertEquals(before.toList(), edited.readBytes().toList(), "existing output must survive untouched")
        assertTrue(main.exists() && overlay.exists(), "a conflict must not cost the originals either")
        assertTrue(
            results.single().status.let { it.startsWith("skipped") || it.startsWith("error") },
            "a conflict must be reported, not counted as a clean combine: ${results.single().status}",
        )
    }

    // Regression (D02): the combiner handed the processor the final output path, so a
    // combine that died partway through — a killed ffmpeg — left a truncated file exactly
    // where the library scans for finished media.
    @Test
    fun failedCombineLeavesNoPartialFileAtTheOutputPath() {
        val main = File(dir, "2023-10-12_VVV-main.mp4").apply { writeBytes(byteArrayOf(1)) }
        val overlay = writePng("2023-10-12_VVV-overlay.png")

        val results = combineAll(FakeProcessor(onVideoCombine = { out ->
            File(out).writeBytes(byteArrayOf(9)) // partial write, then failure
            false
        }))

        assertTrue(results.single().status.startsWith("error"), "status: ${results.single().status}")
        assertTrue(
            !File(dir, "2023-10-12_VVV.mp4").exists(),
            "a truncated combine must not be left where MediaScanner will index it as a finished memory",
        )
        assertTrue(main.exists() && overlay.exists(), "originals must survive")
    }

    // Regression (D02): a failed metadata copy only logged a warning and the originals were
    // deleted anyway, so the capture time that existed nowhere else went with them.
    @Test
    fun originalsSurviveWhenMetadataCopyFails() {
        val main = writePng("2023-10-12_AAA-main.png", 8, 8)
        val overlay = writePng("2023-10-12_AAA-overlay.png", 4, 4)

        val results = combineAll(FakeProcessor(), copyMetadata = { _, _ -> MetadataCopy.Failed })

        assertTrue(
            main.exists() && overlay.exists(),
            "the original still holds metadata the combined file does not — it is the only copy",
        )
        assertTrue(
            results.single().warnings.any { "metadata" in it.lowercase() },
            "the reason the originals were kept must reach the user: ${results.single().warnings}",
        )
    }

    // No exiftool means no metadata reaches the combined image *and* no fallback repairs
    // it: combineAll's date-only batch ends at DesktopMediaProcessor.writeDateMetadata,
    // which returns false without the same tool. So the derivative carries nothing, the
    // originals carry whatever the export embedded, and deleting them ends the only copy.
    // Combining is still allowed to proceed — the user gets a combined image — but the
    // cleanup half waits until the metadata is known to have made it across.
    @Test
    fun originalsSurviveWhenTheMetadataToolIsUnavailable() {
        val main = writePng("2023-10-12_AAA-main.png", 8, 8)
        val overlay = writePng("2023-10-12_AAA-overlay.png", 4, 4)

        val results = combineAll(FakeProcessor(), copyMetadata = { _, _ -> MetadataCopy.Unavailable })

        assertEquals(listOf("combined"), results.map { it.status })
        assertTrue(File(dir, "2023-10-12_AAA.png").length() > 0, "the combine itself still happens")
        assertTrue(
            main.exists() && overlay.exists(),
            "nothing carried the original's metadata across, so the originals are still the only copy",
        )
        assertTrue(
            results.single().warnings.any { "metadata" in it.lowercase() },
            "the user needs to know why the originals are still there: ${results.single().warnings}",
        )
    }

    // A video pair is gated on the same metadata-copy evidence as an image pair (D02): the
    // combined file is a fresh encode, and whatever the export embedded lives only on the
    // original until the copy demonstrably lands.
    @Test
    fun videoOriginalsSurviveWhenMetadataCopyFails() {
        val main = File(dir, "2023-10-12_VVV-main.mp4").apply { writeBytes(byteArrayOf(1)) }
        val overlay = writePng("2023-10-12_VVV-overlay.png")

        val results = combineAll(
            FakeProcessor(onVideoCombine = { out -> File(out).writeBytes(byteArrayOf(1, 2, 3)); true }),
            copyMetadata = { _, _ -> MetadataCopy.Failed },
        )

        assertTrue(main.exists() && overlay.exists(), "the original still holds metadata the combined file does not")
        assertTrue(
            results.single().warnings.any { "metadata" in it.lowercase() },
            "the reason the originals were kept must reach the user: ${results.single().warnings}",
        )
    }

    @Test
    fun videoPairCleansUpWhenMetadataCopySucceeds() {
        val main = File(dir, "2023-10-12_VVV-main.mp4").apply { writeBytes(byteArrayOf(1)) }
        val overlay = writePng("2023-10-12_VVV-overlay.png")

        val results = combineAll(
            FakeProcessor(onVideoCombine = { out -> File(out).writeBytes(byteArrayOf(1, 2, 3)); true }),
            copyMetadata = { _, _ -> MetadataCopy.Copied },
        )

        assertEquals(listOf("combined"), results.map { it.status })
        assertTrue(!main.exists() && !overlay.exists(), "originals are cleaned up once metadata demonstrably copied")
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

    // ── What a result tells the index (D11) ──────────────────────────────────

    // The index is keyed by file name, and the combined file has a new one. Without naming its
    // sources the result gave the pipeline no way to move their entry across, so the file the
    // Library shows lost its GPS badge and its favorite.
    @Test
    fun aCombinedResultNamesItsSourcesMainFirst() {
        val main = writePng("2023-10-12_AAA-main.png", 8, 8)
        val overlay = writePng("2023-10-12_AAA-overlay.png", 4, 4)

        val result = combineAll(FakeProcessor()).single()

        assertEquals(listOf(main.absolutePath, overlay.absolutePath), result.sourcePaths)
        assertTrue(result.metadataCarried)
    }

    // GPS on the combined file is only true if the tags made it across; the result has to say
    // when they did not, or the index repeats the source's claim about a file that lacks it.
    @Test
    fun aCombinedResultSaysWhenTheSourceMetadataDidNotMakeItAcross() {
        writePng("2023-10-12_AAA-main.png", 8, 8)
        writePng("2023-10-12_AAA-overlay.png", 4, 4)

        val failed = combineAll(FakeProcessor(), copyMetadata = { _, _ -> MetadataCopy.Failed }).single()

        assertEquals("combined", failed.status)
        assertEquals(false, failed.metadataCarried)
    }
}
