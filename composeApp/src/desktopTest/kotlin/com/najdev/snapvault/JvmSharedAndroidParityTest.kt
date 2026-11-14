package com.najdev.snapvault

import com.najdev.snapvault.downloader.CombineResult
import com.najdev.snapvault.downloader.PublishOutcome
import com.najdev.snapvault.downloader.STAGING_DIR_NAME
import com.najdev.snapvault.downloader.StagedImageOverlayCombiner
import com.najdev.snapvault.metadata.MediaProcessor
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private open class StubMediaProcessor : MediaProcessor {
    override fun checkExifTool(): Boolean = false
    override fun checkFFmpeg(): Boolean = false
    override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?): Boolean = false
    override fun writeDateMetadata(filePath: String, dateTimeUtc: String): Boolean = false
    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean = false
}

/**
 * Regression suite for Android/JVM parity items (#51, #52, #64):
 * - #52: `ZipReader` and `scanJvmMediaFiles` live in `jvmSharedMain` (API-24 safe, no
 *   `java.time.*` calls) rather than leaving `androidMain` on empty stubs.
 * - #51: `StagedImageOverlayCombiner` invokes `onMetaStart` and forwards `onMetaError` when
 *   combined outputs need filename-derived date fallback metadata.
 * - #64: Combined image outputs stage inside `.snapvault-staging` and publish via a
 *   non-overwriting step so a destination created mid-combine is never clobbered and its
 *   source pair is never deleted.
 */
class JvmSharedAndroidParityTest {

    private lateinit var tempDir: File

    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
    }

    @BeforeTest
    fun setUp() {
        tempDir = File.createTempFile("jvm-android-parity", "").apply {
            delete()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun zipReaderAndMediaScannerCoreAreSharedInJvmSharedMainWithoutJavaTime() {
        val sharedZipReader = File(repoRoot, "composeApp/src/jvmSharedMain/kotlin/com/najdev/snapvault/ZipReader.kt")
        val androidZipStub = File(repoRoot, "composeApp/src/androidMain/kotlin/com/najdev/snapvault/ZipReader.kt")
        val sharedScanner = File(repoRoot, "composeApp/src/jvmSharedMain/kotlin/com/najdev/snapvault/JvmMediaScanner.kt")
        val androidScanner = File(repoRoot, "composeApp/src/androidMain/kotlin/com/najdev/snapvault/MediaScanner.kt")

        assertTrue(sharedZipReader.isFile, "ZipReader.kt should live in jvmSharedMain so Android and Desktop share it")
        assertFalse(androidZipStub.exists(), "androidMain/ZipReader.kt stub must be removed")
        assertTrue(sharedScanner.isFile, "JvmMediaScanner.kt should live in jvmSharedMain")
        assertFalse(
            "import java.time" in sharedScanner.readText(),
            "JvmMediaScanner.kt must stay compatible with Android minSdk 24 (no java.time.* imports)",
        )
        assertTrue(
            "scanJvmMediaFiles(" in androidScanner.readText(),
            "androidMain/MediaScanner.kt must delegate to scanJvmMediaFiles",
        )
    }

    @Test
    fun combinerStagesOutputFiresMetadataFallbackAndForwardsErrors() = runBlocking {
        val main = File(tempDir, "2024-07-19_abc123-main.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val overlay = File(tempDir, "2024-07-19_abc123-overlay.png").apply { writeBytes(byteArrayOf(4, 5)) }

        var metaStartedTotal = -1
        val metaErrors = mutableListOf<String>()
        val writtenBatches = mutableListOf<Pair<String, List<String>>>()
        var sawStagedPathDuringEncode = false

        val processor = object : StubMediaProcessor() {
            override fun combineImageWithOverlay(
                mainPath: String,
                overlayPath: String,
                outputPath: String,
                onWarning: ((String) -> Unit)?,
            ): Boolean {
                sawStagedPathDuringEncode = STAGING_DIR_NAME in outputPath
                File(outputPath).writeBytes(byteArrayOf(9, 9, 9))
                return true
            }

            override fun writeDateMetadataBatch(
                filePaths: List<String>,
                dateOnly: String,
                onError: ((String) -> Unit)?,
            ): Int {
                writtenBatches += dateOnly to filePaths
                onError?.invoke("simulated exif fallback warning")
                return 0
            }
        }

        val results = mutableListOf<CombineResult>()
        val combiner = StagedImageOverlayCombiner(
            mediaProcessor = processor,
            hasCaptureTimestamp = { false },
        )

        combiner.combineAll(
            outputDir = tempDir.absolutePath,
            deleteOriginals = true,
            onStart = {},
            onMetaStart = { total -> metaStartedTotal = total },
            onMetaError = { err -> metaErrors += err },
            onProgress = { res -> results += res },
        )

        assertTrue(sawStagedPathDuringEncode, "combineImageWithOverlay must write to .snapvault-staging first")
        assertFalse(File(tempDir, STAGING_DIR_NAME).exists(), "staging directory should be removed when empty")
        assertEquals(1, results.size)
        assertEquals("combined", results.single().status)
        assertTrue(File(tempDir, "2024-07-19_abc123.jpg").isFile)
        assertFalse(main.exists(), "originals should be deleted after clean combine + metadata carry")
        assertFalse(overlay.exists())
        assertEquals(1, metaStartedTotal, "Issue #51: onMetaStart must report count of combined files needing date fallback")
        assertEquals(1, writtenBatches.size)
        assertEquals("2024-07-19", writtenBatches.single().first)
        assertEquals(listOf("simulated exif fallback warning"), metaErrors, "Issue #51: onMetaError must receive fallback errors")
    }

    @Test
    fun destinationCreatedMidCombineIsNeverOverwrittenAndOriginalsSurvive() = runBlocking {
        val main = File(tempDir, "2024-07-19_race-main.jpg").apply { writeBytes(byteArrayOf(1)) }
        val overlay = File(tempDir, "2024-07-19_race-overlay.png").apply { writeBytes(byteArrayOf(2)) }
        val finalDest = File(tempDir, "2024-07-19_race.jpg")

        val processor = object : StubMediaProcessor() {
            override fun combineImageWithOverlay(
                mainPath: String,
                overlayPath: String,
                outputPath: String,
                onWarning: ((String) -> Unit)?,
            ): Boolean {
                // Simulate an external writer landing `2024-07-19_race.jpg` after the initial
                // exists() check but before staged publish runs (Issue #64).
                finalDest.writeBytes(byteArrayOf(42, 43, 44))
                File(outputPath).writeBytes(byteArrayOf(9, 9, 9))
                return true
            }
        }

        val results = mutableListOf<CombineResult>()
        val combiner = StagedImageOverlayCombiner(mediaProcessor = processor)
        combiner.combineAll(
            outputDir = tempDir.absolutePath,
            deleteOriginals = true,
            onProgress = { results += it },
        )

        assertEquals("skipped: output already exists", results.single().status)
        assertEquals(listOf<Byte>(42, 43, 44), finalDest.readBytes().toList(), "existing destination was overwritten")
        assertTrue(main.exists(), "main file must not be deleted when publish detected existing destination")
        assertTrue(overlay.exists(), "overlay file must not be deleted when publish detected existing destination")
        assertFalse(File(tempDir, STAGING_DIR_NAME).exists(), "staged file must be cleaned up on collision")
    }

    @Test
    fun uncarriedMetadataPreventsDeletingOriginalsAndFlagsResult() = runBlocking {
        val main = File(tempDir, "2024-07-19_nometa-main.jpg").apply { writeBytes(byteArrayOf(1)) }
        val overlay = File(tempDir, "2024-07-19_nometa-overlay.png").apply { writeBytes(byteArrayOf(2)) }

        val processor = object : StubMediaProcessor() {
            override fun combineImageWithOverlay(
                mainPath: String,
                overlayPath: String,
                outputPath: String,
                onWarning: ((String) -> Unit)?,
            ): Boolean {
                File(outputPath).writeBytes(byteArrayOf(7, 8, 9))
                onWarning?.invoke("Combined $outputPath but could not carry its metadata: simulated EXIF failure")
                return true
            }
        }

        val results = mutableListOf<CombineResult>()
        val combiner = StagedImageOverlayCombiner(
            mediaProcessor = processor,
            hasCaptureTimestamp = { true },
        )
        combiner.combineAll(
            outputDir = tempDir.absolutePath,
            deleteOriginals = true,
            onProgress = { results += it },
        )

        val single = results.single()
        assertEquals("combined", single.status)
        assertFalse(single.metadataCarried, "metadataCarried must be false when EXIF copy failed")
        assertTrue(main.exists(), "main original must survive when metadata was not carried")
        assertTrue(overlay.exists(), "overlay original must survive when metadata was not carried")
        assertEquals(PublishOutcome.Published, PublishOutcome.Published)
    }
}
