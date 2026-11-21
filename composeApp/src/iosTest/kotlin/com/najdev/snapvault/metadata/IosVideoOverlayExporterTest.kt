package com.najdev.snapvault.metadata

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.AVFoundation.*
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import platform.Foundation.*
import platform.CoreMedia.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalForeignApi::class)
class IosVideoOverlayExporterTest {
    // Regression: the Core Animation compositor trapped in IOSurface/XPC during export,
    // quitting the app before any output could be published. Exercise the real native encoder.
    // These standalone tests assert export structure; displayed pixels are verified in app-hosted QA.
    @Test
    fun nativeExportCompletesWithoutCrashingAndRetainsAudioAndSources() = runTest {
        verifyExport("source.mp4", 120.0, 160.0)
    }

    @Test
    fun aRotatedClipExportsInItsDisplayOrientation() = runTest {
        verifyExport("rotated.mp4", 160.0, 120.0)
    }

    @Test
    fun cancellationDrainsTheEncoderAndKeepsOnlyTheSources() = runTest {
        val fixture = fixture("source.mp4")
        try {
            withContext(Dispatchers.Default) {
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    exportIosVideoOverlay(fixture.source, fixture.overlay, fixture.output)
                }
                try {
                    withTimeout(10_000) {
                        while (job.isActive && fixture.names().none { it.endsWith(".stage") }) delay(1)
                    }
                    assertTrue(job.isActive, "The test must cancel an active encoder, not an already completed export")
                    assertTrue(fixture.names().any { it.endsWith(".stage") }, "A real native output must exist before cancellation")
                    job.cancelAndJoin()
                    assertTrue(job.isCancelled)
                    assertEquals(setOf("source.mp4", "overlay.png"), fixture.names())
                    delay(100)
                    assertEquals(setOf("source.mp4", "overlay.png"), fixture.names(), "No late encoder output may reappear")
                } finally {
                    job.cancelAndJoin()
                }
            }
        } finally {
            fixture.cleanup()
        }
    }

    @Test
    fun anExistingOutputIsNeverOverwrittenOrTreatedAsThisRunsResult() = runTest {
        val fixture = fixture("source.mp4")
        val manager = NSFileManager.defaultManager
        try {
            assertTrue(manager.copyItemAtPath(fixture.source, fixture.output, null))
            val original = assertNotNull(manager.contentsAtPath(fixture.output))
            assertFalse(exportIosVideoOverlay(fixture.source, fixture.overlay, fixture.output))
            assertTrue(original.isEqualToData(assertNotNull(manager.contentsAtPath(fixture.output))))
            assertTrue(manager.fileExistsAtPath(fixture.source))
            assertTrue(manager.fileExistsAtPath(fixture.overlay))
            assertTrue(fixture.names().none { it.endsWith(".stage") })
        } finally {
            fixture.cleanup()
        }
    }

    // The probe must see HDR in the source or the export assertions below prove nothing.
    @Test
    fun theProbeSeesHdrInTheSourceFixturesAndNotInTheSdrOne() {
        for ((name, transfer) in HDR_FIXTURES) {
            val fixture = fixture(name)
            try {
                val probe = probe(fixture.source)
                println("HDR-PROBE source $name -> codec=${probe.codec} transfer=${probe.transfer} primaries=${probe.primaries} matrix=${probe.matrix} hdr=${probe.hdrCharacteristic}")
                assertEquals(HEVC, probe.codec, name)
                assertEquals(transfer, probe.transfer, name)
                assertEquals("ITU_R_2020", probe.primaries, name)
                assertTrue(probe.hdrCharacteristic, "$name source must report the HDR characteristic")
            } finally { fixture.cleanup() }
        }
        val sdr = fixture("source.mp4")
        try {
            assertFalse(probe(sdr.source).hdrCharacteristic)
        } finally { sdr.cleanup() }
    }

    // Regression (found 2026-11-14 on the iPhone 17 Pro simulator): with AVAssetExportPresetHighestQuality
    // an HLG source exported as H.264 ('avc1') tagged ITU_R_709_2 for transfer, primaries and matrix, with no
    // HDR characteristic: a silent HDR -> SDR downgrade. HLG/PQ must keep their transfer function, BT.2020
    // primaries, HEVC codec and HDR characteristic. A refused export also fails (no vacuous pass).
    // Every fixture is exported and probed before any assertion, so one failure cannot hide another.
    // Standalone frames are not rendered faithfully here, so this checks stream tags, not pixels.
    @Test
    fun hdrSourcesKeepTheirTransferFunctionThroughExport() = runTest {
        class Result(
            val name: String, val transfer: String, val completed: Boolean, val sourceRetained: Boolean,
            val stageLeft: Boolean, val out: StreamProbe?, val width: Double?, val height: Double?, val audio: Int?,
        )
        val results = HDR_FIXTURES.map { (name, transfer) ->
            val fixture = fixture(name)
            try {
                val completed = exportIosVideoOverlay(fixture.source, fixture.overlay, fixture.output)
                val written = NSFileManager.defaultManager.fileExistsAtPath(fixture.output)
                val exported = if (written) AVURLAsset(NSURL.fileURLWithPath(fixture.output), null) else null
                val size = exported?.let { (it.tracksWithMediaType(AVMediaTypeVideo).single() as AVAssetTrack).naturalSize }
                Result(
                    name, transfer, completed,
                    sourceRetained = NSFileManager.defaultManager.fileExistsAtPath(fixture.source),
                    stageLeft = fixture.names().any { it.endsWith(".stage") },
                    out = if (written) probe(fixture.output) else null,
                    width = size?.useContents { width }, height = size?.useContents { height },
                    audio = exported?.tracksWithMediaType(AVMediaTypeAudio)?.size,
                ).also { r ->
                    val o = r.out
                    println("HDR-PROBE $name -> completed=$completed codec=${o?.codec} transfer=${o?.transfer} primaries=${o?.primaries} matrix=${o?.matrix} hdr=${o?.hdrCharacteristic}")
                }
            } finally {
                archiveOutput(fixture, name)
                fixture.cleanup()
            }
        }
        for (r in results) {
            assertTrue(r.completed, "${r.name} export must complete")
            assertTrue(r.sourceRetained, "${r.name} source retained")
            assertFalse(r.stageLeft, "${r.name} left staged output")
            val out = assertNotNull(r.out, "${r.name} produced no output")
            assertEquals(r.transfer, out.transfer, "${r.name} exported with a different transfer function (silent HDR downgrade)")
            assertTrue(out.hdrCharacteristic, "${r.name} export lost the HDR characteristic")
            assertEquals(HEVC, out.codec, "${r.name} must stay HEVC (10-bit capable), not be re-encoded as H.264")
            assertEquals("ITU_R_2020", out.primaries, "${r.name} lost its BT.2020 primaries")
            assertEquals(120.0, r.width)
            assertEquals(160.0, r.height)
            assertEquals(1, r.audio)
        }
    }

    @Test
    fun anSdrSourceStaysSdrAfterExport() = runTest {
        val fixture = fixture("source.mp4")
        try {
            assertTrue(exportIosVideoOverlay(fixture.source, fixture.overlay, fixture.output))
            val out = probe(fixture.output)
            println("HDR-PROBE source.mp4 -> codec=${out.codec} transfer=${out.transfer} primaries=${out.primaries} matrix=${out.matrix} hdr=${out.hdrCharacteristic}")
            assertFalse(out.hdrCharacteristic, "an SDR clip must not be tagged HDR")
        } finally { fixture.cleanup() }
    }

    private class StreamProbe(val codec: UInt, val transfer: String?, val primaries: String?, val matrix: String?, val hdrCharacteristic: Boolean)

    private fun probe(path: String): StreamProbe {
        val track = AVURLAsset(NSURL.fileURLWithPath(path), null).tracksWithMediaType(AVMediaTypeVideo).single() as AVAssetTrack
        // formatDescriptions holds bridged Objective-C objects (__NSCFType), not CPointers: a plain
        // cast throws ClassCastException. Bridge with an owned reference and release it afterwards.
        val retained = CFBridgingRetain(track.formatDescriptions.single()) ?: error("no format description")
        try {
            val description: CMFormatDescriptionRef = retained.reinterpret()
            // The "Get" rule returns an unowned reference: retain before the bridge takes ownership.
            // An absent key (SDR clips, untagged output) returns NULL; CFRetain(NULL) would abort the runner.
            fun extension(key: platform.CoreFoundation.CFStringRef?): String? {
                val value = CMFormatDescriptionGetExtension(description, key) ?: return null
                return CFBridgingRelease(platform.CoreFoundation.CFRetain(value)) as? String
            }
            return StreamProbe(
                codec = CMFormatDescriptionGetMediaSubType(description),
                transfer = extension(kCMFormatDescriptionExtension_TransferFunction),
                primaries = extension(kCMFormatDescriptionExtension_ColorPrimaries),
                matrix = extension(kCMFormatDescriptionExtension_YCbCrMatrix),
                hdrCharacteristic = track.hasMediaCharacteristic(AVMediaCharacteristicContainsHDRVideo),
            )
        } finally {
            platform.CoreFoundation.CFRelease(retained)
        }
    }

    private companion object {
        const val HEVC = 0x68766331u // 'hvc1'
        val HDR_FIXTURES = listOf("hdr-hlg.mp4" to "ITU_R_2100_HLG", "hdr-pq.mp4" to "SMPTE_ST_2084_PQ")
    }

    private class Fixture(val directory: String) {
        val source = "$directory/source.mp4"
        val overlay = "$directory/overlay.png"
        val output = "$directory/export.mp4"
        fun names(): Set<String> = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null)
            .orEmpty().filterIsInstance<String>().toSet()
        fun cleanup() { NSFileManager.defaultManager.removeItemAtPath(directory, null) }
    }

    private fun fixture(fileName: String): Fixture {
        val resources = NSProcessInfo.processInfo.environment["SNAPVAULT_TEST_RESOURCES"] as? String
            ?: error("Native test resources were not configured")
        val manager = NSFileManager.defaultManager
        val fixture = Fixture(NSTemporaryDirectory() + NSUUID().UUIDString)
        assertTrue(manager.createDirectoryAtPath(fixture.directory, true, null, null))
        assertTrue(manager.copyItemAtPath("$resources/video-overlay/$fileName", fixture.source, null))
        assertTrue(manager.copyItemAtPath("$resources/video-overlay/overlay.png", fixture.overlay, null))
        return fixture
    }

    private suspend fun verifyExport(fileName: String, width: Double, height: Double) {
        val fixture = fixture(fileName)
        val manager = NSFileManager.defaultManager
        try {
            assertTrue(exportIosVideoOverlay(fixture.source, fixture.overlay, fixture.output))
            assertTrue(manager.fileExistsAtPath(fixture.source))
            assertTrue(manager.fileExistsAtPath(fixture.overlay))
            val exported = AVURLAsset(NSURL.fileURLWithPath(fixture.output), null)
            val video = exported.tracksWithMediaType(AVMediaTypeVideo).single() as AVAssetTrack
            assertEquals(width, video.naturalSize.useContents { this.width })
            assertEquals(height, video.naturalSize.useContents { this.height })
            assertEquals(1, exported.tracksWithMediaType(AVMediaTypeAudio).size)
        } finally {
            archiveOutput(fixture, fileName)
            fixture.cleanup()
        }
    }

    private fun archiveOutput(fixture: Fixture, fileName: String) {
        val manager = NSFileManager.defaultManager
        val artifacts = NSProcessInfo.processInfo.environment["SNAPVAULT_TEST_OUTPUTS"] as? String
        if (artifacts != null && manager.fileExistsAtPath(fixture.output)) {
            manager.createDirectoryAtPath(artifacts, true, null, null)
            val artifact = "$artifacts/$fileName"
            manager.removeItemAtPath(artifact, null)
            manager.copyItemAtPath(fixture.output, artifact, null)
        }
    }
}
