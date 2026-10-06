package com.najdev.snapvault.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-pair decisions the Android runner makes after a combine attempt. They live in common
 * code because the consequential one is destructive — it decides whether the user's original
 * photo is deleted — and androidMain has no test source set to assert it in.
 */
class OverlayCombineOutcomeTest {

    private val pair = OverlayPairNames(
        stem = "2017-07-13_abc",
        mainName = "2017-07-13_abc-main.jpg",
        overlayName = "2017-07-13_abc-overlay.png",
        outputName = "2017-07-13_abc.jpg",
        isVideo = false,
    )

    // The one that loses data if it is wrong: a failed combine leaves no output, so deleting
    // the main file would destroy the only copy of the photo.
    @Test
    fun originalsAreNeverDeletedUnlessTheCombineActuallySucceeded() {
        assertTrue(mayDeleteOriginals(OverlayCombineStatus.Combined, deleteRequested = true))
        assertFalse(
            mayDeleteOriginals(OverlayCombineStatus.Failed, deleteRequested = true),
            "a failed combine produced no output — deleting the main file destroys the only copy",
        )
        assertFalse(
            mayDeleteOriginals(OverlayCombineStatus.SkippedVideo, deleteRequested = true),
            "a skipped video was never combined — its original is the only copy",
        )
        assertFalse(
            mayDeleteOriginals(OverlayCombineStatus.SkippedAnimated, deleteRequested = true),
            "a skipped animation was never combined — deleting it loses every frame",
        )
    }

    // The worst case this enum exists to prevent. A .gif combine used to report success:
    // the decoder handed back frame one, the encoder wrote it as JPEG into a file still
    // named .gif, and the status came back Combined — which cleared the animated original
    // for deletion. Frames two onward existed nowhere else.
    @Test
    fun anAnimatedOriginalIsNeverClearedForDeletion() {
        val gif = pair.copy(
            mainName = "g-main.gif",
            overlayName = "g-overlay.png",
            outputName = "g.gif",
            isAnimatedImage = true,
        )
        val result = overlayCombineResult(
            gif,
            mainPath = "/out/g-main.gif",
            overlayPath = "/out/g-overlay.png",
            outputPath = "/out/g.gif",
            status = OverlayCombineStatus.SkippedAnimated,
            warnings = emptyList(),
        )
        assertTrue(result.status.startsWith("skipped"), "was '${'$'}{result.status}'")
        assertEquals(
            "/out/g-main.gif",
            result.outputPath,
            "nothing new was written, so the result must point at the untouched original",
        )
        assertFalse(
            mayDeleteOriginals(OverlayCombineStatus.SkippedAnimated, deleteRequested = true),
            "the animated original is the only copy of every frame after the first",
        )
    }

    // Regression: the mobile runners handed the final path straight to their encoders.
    // Android wrote to it directly and iOS removed whatever was there before moving its
    // output into place, so re-importing an export replaced a combined image the user had
    // edited, came back Combined, and then deleted both originals — no copy of anything
    // survived. Desktop has refused an existing output since D02; this is the same rule.
    @Test
    fun anExistingOutputIsNeverReplacedOrClearedForDeletion() {
        val status = overlayCombineSkipStatus(pair, outputExists = true)
        assertEquals(OverlayCombineStatus.SkippedExistingOutput, status)
        assertFalse(
            mayDeleteOriginals(status!!, deleteRequested = true),
            "a conflict wrote nothing — the originals are still the only copies of this pair",
        )

        val result = overlayCombineResult(
            pair,
            mainPath = "/out/2017-07-13_abc-main.jpg",
            overlayPath = "/out/2017-07-13_abc-overlay.png",
            outputPath = "/out/2017-07-13_abc.jpg",
            status = status,
            warnings = emptyList(),
        )
        assertEquals("skipped: output already exists", result.status)
        assertEquals(
            "/out/2017-07-13_abc-main.jpg",
            result.outputPath,
            "the file already at the output path is not something this run produced",
        )
    }

    // The conflict is checked before the kind of media: an existing output is reported as
    // such whatever the pair is, rather than as a video or animation skip that hides it.
    @Test
    fun anExistingOutputTakesPrecedenceOverEveryOtherSkip() {
        val video = pair.copy(mainName = "v-main.mp4", outputName = "v.mp4", isVideo = true)
        val gif = pair.copy(mainName = "g-main.gif", outputName = "g.gif", isAnimatedImage = true)
        for (p in listOf(pair, video, gif)) {
            assertEquals(
                OverlayCombineStatus.SkippedExistingOutput,
                overlayCombineSkipStatus(p, outputExists = true),
                p.mainName,
            )
        }
    }

    @Test
    fun aPairWithNoConflictIsSkippedOnlyForWhatItIs() {
        assertEquals(null, overlayCombineSkipStatus(pair, outputExists = false), "a still image is attempted")
        assertEquals(
            OverlayCombineStatus.SkippedVideo,
            overlayCombineSkipStatus(pair.copy(isVideo = true), outputExists = false),
        )
        assertEquals(
            OverlayCombineStatus.SkippedAnimated,
            overlayCombineSkipStatus(pair.copy(isAnimatedImage = true), outputExists = false),
        )
    }

    @Test
    fun deletingIsStillOptOutWhenTheCallerDidNotAskForIt() {
        for (status in OverlayCombineStatus.entries) {
            assertFalse(
                mayDeleteOriginals(status, deleteRequested = false),
                "$status must not delete when the caller did not ask",
            )
        }
    }

    // A video that was never composited must not be reported as combined: the Library reads
    // this status to badge a file as having its overlay burned in.
    @Test
    fun aSkippedVideoIsNotReportedAsCombined() {
        val result = overlayCombineResult(
            pair.copy(isVideo = true, mainName = "v-main.mp4", outputName = "v.mp4"),
            mainPath = "/out/v-main.mp4",
            overlayPath = "/out/v-overlay.png",
            outputPath = "/out/v.mp4",
            status = OverlayCombineStatus.SkippedVideo,
            warnings = emptyList(),
        )
        assertTrue(result.status.startsWith("skipped"), "was '${result.status}'")
        assertFalse("combined" == result.status)
        assertEquals("/out/v-main.mp4", result.outputPath, "a skipped video still points at its original")
    }

    @Test
    fun aFailedCombinePointsAtTheUntouchedOriginal() {
        val result = overlayCombineResult(
            pair, "/out/m.jpg", "/out/o.png", "/out/c.jpg",
            OverlayCombineStatus.Failed, emptyList(),
        )
        assertTrue(result.status.startsWith("error"), "was '${result.status}'")
        assertEquals("/out/m.jpg", result.outputPath)
    }

    @Test
    fun aSuccessfulCombinePointsAtTheNewFileAndNamesItsSources() {
        val result = overlayCombineResult(
            pair, "/out/m.jpg", "/out/o.png", "/out/c.jpg",
            OverlayCombineStatus.Combined, emptyList(),
        )
        assertEquals("combined", result.status)
        assertEquals("/out/c.jpg", result.outputPath)
        assertEquals(listOf("/out/m.jpg", "/out/o.png"), result.sourcePaths)
    }

    // GPS is a claim about the file's own tags. If carrying them over failed, the combined
    // file must not inherit the source's location — see CombineResult.metadataCarried.
    @Test
    fun aCombineWhoseMetadataDidNotCarryDoesNotClaimItDid() {
        val carried = overlayCombineResult(
            pair, "/out/m.jpg", "/out/o.png", "/out/c.jpg",
            OverlayCombineStatus.Combined, emptyList(),
        )
        assertTrue(carried.metadataCarried)

        val lost = overlayCombineResult(
            pair, "/out/m.jpg", "/out/o.png", "/out/c.jpg",
            OverlayCombineStatus.Combined,
            warnings = listOf("Combined c.jpg but could not carry its metadata: boom"),
        )
        assertFalse(lost.metadataCarried, "a file whose tags did not carry must not claim its source's GPS")
    }

    @Test
    fun theUuidIsTakenFromTheStemAfterTheDatePrefix() {
        val result = overlayCombineResult(
            pair, "/out/m.jpg", "/out/o.png", "/out/c.jpg",
            OverlayCombineStatus.Combined, emptyList(),
        )
        assertEquals("abc", result.uuid)
    }

    @Test
    fun aStemWithNoDatePrefixIsUsedWhole() {
        val result = overlayCombineResult(
            pair.copy(stem = "loose"), "/out/m.jpg", "/out/o.png", "/out/c.jpg",
            OverlayCombineStatus.Combined, emptyList(),
        )
        assertEquals("loose", result.uuid)
    }
}
