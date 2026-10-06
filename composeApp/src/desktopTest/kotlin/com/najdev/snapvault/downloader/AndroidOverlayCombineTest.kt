package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidOverlayCombineTest {
    private lateinit var dir: File
    private lateinit var main: File
    private lateinit var overlay: File
    private val pair = OverlayPairNames(
        stem = "2023-10-12_AAA",
        mainName = "2023-10-12_AAA-main.png",
        overlayName = "2023-10-12_AAA-overlay.png",
        outputName = "2023-10-12_AAA.png",
        isVideo = false,
    )

    @BeforeTest
    fun setUp() {
        dir = File.createTempFile("android-combine-test", "").apply { delete(); mkdirs() }
        main = File(dir, pair.mainName).apply { writeText("main") }
        overlay = File(dir, pair.overlayName).apply { writeText("overlay") }
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private class FakeProcessor(
        val onCombine: (String, ((String) -> Unit)?) -> Boolean,
    ) : MediaProcessor {
        override fun checkExifTool() = false
        override fun checkFFmpeg() = false
        override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = false
        override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = false
        override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = false
        override fun combineImageWithOverlay(
            mainPath: String,
            overlayPath: String,
            outputPath: String,
            onWarning: ((String) -> Unit)?,
        ) = onCombine(outputPath, onWarning)
    }

    private fun combine(processor: MediaProcessor, deleteOriginals: Boolean = true) =
        combineAndroidOverlayPair(dir, pair, main, overlay, deleteOriginals, processor)

    // Regression (#56): Android treated a successful pixel encode as permission to delete
    // both originals even when EXIF copying warned that their metadata had not survived.
    @Test
    fun failedMetadataCopyKeepsOriginals() {
        val result = combine(FakeProcessor { output, warn ->
            File(output).writeText("composite")
            warn?.invoke("Combined image but could not carry its metadata: failed")
            true
        })

        assertTrue(main.exists() && overlay.exists())
        assertTrue(File(dir, pair.outputName).exists())
        assertEquals("combined", result.status)
        assertFalse(result.metadataCarried)
    }

    @Test
    fun successfulMetadataCopyPermitsDeletion() {
        val result = combine(FakeProcessor { output, _ ->
            File(output).writeText("composite")
            true
        })

        assertFalse(main.exists())
        assertFalse(overlay.exists())
        assertTrue(File(dir, pair.outputName).exists())
        assertTrue(result.metadataCarried)
    }

    // Regression (#57): a processor that died after opening the final path left partial
    // bytes there. The next import then skipped the pair as an existing output.
    @Test
    fun interruptedEncodeDoesNotCreatePartialOutput() {
        val failed = combine(FakeProcessor { output, _ ->
            File(output).writeText("partial")
            error("encode interrupted")
        })

        val destination = File(dir, pair.outputName)
        assertTrue(failed.status.startsWith("error"))
        assertFalse(destination.exists())
        assertTrue(main.exists() && overlay.exists())
        assertFalse(File(dir, STAGING_DIR_NAME).exists())

        val retried = combine(FakeProcessor { output, _ ->
            File(output).writeText("complete")
            true
        })
        assertEquals("combined", retried.status)
        assertEquals("complete", destination.readText())
        assertFalse(File(dir, STAGING_DIR_NAME).exists())
    }

    @Test
    fun existingValidOutputRemainsUntouched() {
        val destination = File(dir, pair.outputName).apply { writeText("previous result") }
        val result = combine(FakeProcessor { output, _ ->
            File(output).writeText("replacement")
            true
        })

        assertEquals("skipped: output already exists", result.status)
        assertEquals("previous result", destination.readText())
        assertTrue(main.exists() && overlay.exists())
        assertFalse(File(dir, STAGING_DIR_NAME).exists())
    }
}
