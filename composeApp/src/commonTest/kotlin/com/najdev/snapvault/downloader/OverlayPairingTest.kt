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

    // A .gif main is readable by every image decoder here, writable by none of them without
    // flattening: BitmapFactory, ImageIO and CGImageSource all hand back frame one only, and
    // the encoders on the combine path write a single frame back. Combining one therefore
    // destroyed the animation *and* wrote JPEG bytes into a file still named .gif — and
    // because that reported success, mayDeleteOriginals then deleted the animated original.
    // IosMediaProcessor already refuses GIFs on the metadata path for exactly this reason
    // (singleFrameUnsafeExtensions); the combine path never got the same treatment.
    @Test
    fun anAnimatedGifPairIsFlaggedRatherThanFlattenedToOneFrame() {
        val pairs = findOverlayPairNames(listOf("g-main.gif", "g-overlay.png"))
        assertEquals(1, pairs.size)
        assertTrue(
            pairs[0].isAnimatedImage,
            "a .gif main must be flagged: compositing it keeps frame one and discards the rest",
        )
    }

    @Test
    fun stillImageFormatsAreNotFlaggedAsAnimated() {
        for (ext in listOf("jpg", "jpeg", "png", "heic", "heif", "webp", "tiff", "tif")) {
            val pairs = findOverlayPairNames(listOf("i-main.$ext", "i-overlay.png"))
            assertTrue(
                !pairs[0].isAnimatedImage,
                ".$ext is a single-frame format and must still be composited",
            )
        }
    }

    // Distinct from isVideo: a GIF is not video (the Library treats it as an image, and
    // SupportedMediaExtensions.IMAGE lists it), it just cannot survive a re-encode.
    @Test
    fun anAnimatedImageIsNotAlsoClassifiedAsVideo() {
        val pairs = findOverlayPairNames(listOf("g-main.gif", "g-overlay.png"))
        assertTrue(!pairs[0].isVideo, "a .gif is an image; classifying it as video mislabels it")
    }

    // The platform encoders are selected by the OUTPUT extension, not the source's detected
    // type — IosMediaProcessor.imageDestinationUti maps exactly jpg/jpeg/png/tif/tiff and
    // refuses anything else, and the desktop combiner picks PNG-or-JPEG the same way. That
    // only holds because pair discovery narrows every still-image pair to this set. Adding a
    // format to SupportedMediaExtensions.IMAGE without deciding what writes it back would
    // land bytes in a file whose extension lies; this is the test that catches it.
    @Test
    fun everyStillImagePairResolvesToAnExtensionAnEncoderCanWrite() {
        val writable = setOf("jpg", "jpeg", "png", "tif", "tiff")
        val stillImages = com.najdev.snapvault.metadata.SupportedMediaExtensions.IMAGE
        for (ext in stillImages) {
            val pairs = findOverlayPairNames(listOf("i-main.$ext", "i-overlay.png"))
            assertEquals(1, pairs.size, "no pair found for .$ext")
            val pair = pairs[0]
            // Animated formats never reach an encoder at all, so they are exempt.
            if (pair.isAnimatedImage) continue
            assertTrue(
                pair.outputName.substringAfterLast('.').lowercase() in writable,
                ".$ext produced output '${'$'}{pair.outputName}', which no image encoder here writes",
            )
        }
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
