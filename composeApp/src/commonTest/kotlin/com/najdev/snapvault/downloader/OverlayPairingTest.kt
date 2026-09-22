package com.najdev.snapvault.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pair discovery was written twice — once in the desktop OverlayCombiner and once in the
 * Android runner — and the two copies had already drifted: the Android one carried a
 * hand-maintained video list missing "m4v", which is the exact BUG-18 mistake
 * [com.najdev.snapvault.metadata.SupportedMediaExtensions] exists to prevent. It is pure
 * name arithmetic, so it belongs in common code where one test covers every platform.
 */
class OverlayPairingTest {

    @Test
    fun aMainAndOverlaySharingAStemArePaired() {
        val pairs = findOverlayPairNames(
            listOf("2017-07-13_abc-main.jpg", "2017-07-13_abc-overlay.png")
        )
        assertEquals(1, pairs.size)
        assertEquals("2017-07-13_abc", pairs[0].stem)
        assertEquals("2017-07-13_abc-main.jpg", pairs[0].mainName)
        assertEquals("2017-07-13_abc-overlay.png", pairs[0].overlayName)
        assertEquals("2017-07-13_abc.jpg", pairs[0].outputName)
    }

    // The stem carries the date prefix, so two memories that happen to share a UUID do not
    // collide — this is why matching is on the physical name rather than a parsed UUID.
    @Test
    fun aDatePrefixKeepsDuplicateUuidsApart() {
        val pairs = findOverlayPairNames(
            listOf(
                "2017-07-13_dupe-main.jpg", "2017-07-13_dupe-overlay.png",
                "2019-01-02_dupe-main.jpg", "2019-01-02_dupe-overlay.png",
            )
        ).sortedBy { it.stem }
        assertEquals(listOf("2017-07-13_dupe", "2019-01-02_dupe"), pairs.map { it.stem })
    }

    @Test
    fun aMainWithNoOverlayIsNotAPair() {
        assertEquals(emptyList(), findOverlayPairNames(listOf("lonely-main.jpg")))
    }

    @Test
    fun anOverlayWithNoMainIsNotAPair() {
        assertEquals(emptyList(), findOverlayPairNames(listOf("lonely-overlay.png")))
    }

    // Regression for BUG-18 as it reached the Android runner: the duplicated copy classified
    // video by a hardcoded set that omitted m4v, so an .m4v pair was sent down the image
    // compositing path — which decodes it as a bitmap and fails.
    @Test
    fun everyKnownVideoExtensionIsClassifiedAsVideoIncludingM4v() {
        for (ext in listOf("mp4", "mov", "avi", "mkv", "m4v")) {
            val pairs = findOverlayPairNames(listOf("v-main.$ext", "v-overlay.png"))
            assertEquals(1, pairs.size, "no pair found for .$ext")
            assertTrue(pairs[0].isVideo, ".$ext must be treated as video, not composited as an image")
        }
    }

    @Test
    fun imagesAreNotClassifiedAsVideo() {
        for (ext in listOf("jpg", "png", "heic", "webp", "gif")) {
            val pairs = findOverlayPairNames(listOf("i-main.$ext", "i-overlay.png"))
            assertTrue(!pairs[0].isVideo, ".$ext must not be treated as video")
        }
    }

    // HEIC/WebP cannot be written back by the image encoders on either platform; both fall
    // back to JPEG, so the output name has to say jpg upfront or the file lands mislabelled.
    @Test
    fun formatsThatCannotBeWrittenBackGetAJpgOutputName() {
        for (ext in listOf("heic", "heif", "webp")) {
            val pairs = findOverlayPairNames(listOf("x-main.$ext", "x-overlay.png"))
            assertEquals("x.jpg", pairs[0].outputName, ".$ext must fall back to a .jpg output")
        }
    }

    @Test
    fun aVideoKeepsItsOwnContainerExtension() {
        val pairs = findOverlayPairNames(listOf("v-main.mov", "v-overlay.png"))
        assertEquals("v.mov", pairs[0].outputName, "a video must not be renamed to .jpg")
    }

    @Test
    fun namesThatAreOnlyTheMarkerAreIgnored() {
        // "-main.jpg" has an empty stem; pairing on it would collide every such file.
        assertEquals(emptyList(), findOverlayPairNames(listOf("-main.jpg", "-overlay.png")))
    }

    @Test
    fun unrelatedFilesAreLeftAlone() {
        val pairs = findOverlayPairNames(
            listOf("holiday.jpg", "notes.txt", "a-main.jpg", "a-overlay.png")
        )
        assertEquals(1, pairs.size)
        assertEquals("a", pairs[0].stem)
    }
}

/**
 * [com.najdev.snapvault.metadata.MediaProcessor.combineImageWithOverlay] defaults to false so
 * adding it did not break the platforms that do not implement it. That default has
 * consequences — it decides whether an un-composited main file is quietly kept or reported as
 * a failure — so it is asserted rather than assumed.
 */
class CombineImageDefaultTest {

    private class BareProcessor : com.najdev.snapvault.metadata.MediaProcessor {
        override fun checkExifTool() = true
        override fun checkFFmpeg() = true
        override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = true
        override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = true
        override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = true
    }

    @Test
    fun aPlatformThatCannotCompositeImagesReportsNotCombinedRatherThanThrowing() {
        assertEquals(
            false,
            BareProcessor().combineImageWithOverlay("/a-main.jpg", "/a-overlay.png", "/a.jpg"),
            "the default must report 'not combined' so the main file is kept untouched",
        )
    }
}
