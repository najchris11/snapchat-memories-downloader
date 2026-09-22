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
