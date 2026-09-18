package com.najdev.snapvault.downloader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D20: whether an import that will not fit as it stands would fit if each source ZIP were
 * deleted as soon as its contents were imported.
 *
 * Snapchat's media is already compressed, so extracting an archive costs about what the archive
 * itself occupies: deleting each one as it finishes keeps the total roughly level instead of
 * doubling it. What the import then needs at any moment is room for one archive's contents plus
 * the processing reserve, not for every archive at once.
 */
class LowSpacePlanTest {

    private val gb = 1024L * 1024 * 1024
    private fun archive(name: String, bytes: Long, onOutputVolume: Boolean = true) =
        ArchiveSpace(path = "/zips/$name", requiredBytes = bytes, archiveBytes = bytes, onOutputVolume = onOutputVolume)

    @Test
    fun anImportThatDoesNotFitAtOnceFitsOneArchiveAtATime() {
        val archives = List(5) { archive("part$it.zip", 4 * gb) }

        val plan = planLowSpaceImport(archives, availableBytes = 6 * gb, reserveBytes = gb)

        assertTrue(plan.fits, "5 GB free after each archive is enough to take the next one")
        assertEquals(20 * gb, plan.reclaimableBytes)
    }

    @Test
    fun anArchiveTooBigForTheFreeSpaceDoesNotFitEvenOneAtATime() {
        val archives = listOf(archive("part1.zip", 4 * gb), archive("part2.zip", 9 * gb))

        val plan = planLowSpaceImport(archives, availableBytes = 6 * gb, reserveBytes = gb)

        assertFalse(plan.fits, "no amount of deleting makes room for a 9 GB archive in 6 GB")
    }

    // Deleting a ZIP on another drive frees nothing where the library is being written, so
    // offering the mode there would be a false promise.
    @Test
    fun archivesOnAnotherVolumeAreNotWorthDeleting() {
        val archives = List(3) { archive("part$it.zip", 4 * gb, onOutputVolume = false) }

        val plan = planLowSpaceImport(archives, availableBytes = 6 * gb, reserveBytes = gb)

        assertFalse(plan.fits)
        assertEquals(0, plan.reclaimableBytes, "nothing on this volume would be freed")
    }

    // Smallest first: the tightest moment is the first archive, before anything has been
    // deleted, so start with the one needing least room.
    @Test
    fun theOrderStartsWithTheSmallestArchive() {
        val archives = listOf(archive("big.zip", 5 * gb), archive("small.zip", 2 * gb))

        val plan = planLowSpaceImport(archives, availableBytes = 7 * gb, reserveBytes = gb)

        assertEquals(listOf("/zips/small.zip", "/zips/big.zip"), plan.archives.map { it.path })
        assertTrue(plan.fits)
    }

    // An archive whose media is already in the output folder writes nothing but still frees its
    // own bytes when deleted.
    @Test
    fun anArchiveWithNothingLeftToExtractStillFreesItsSpace() {
        val archives = listOf(
            ArchiveSpace("/zips/done.zip", requiredBytes = 0, archiveBytes = 3 * gb, onOutputVolume = true),
            archive("rest.zip", 5 * gb),
        )

        val plan = planLowSpaceImport(archives, availableBytes = 4 * gb, reserveBytes = gb)

        assertTrue(plan.fits, "deleting the finished archive makes room for the other")
    }

    // Extraction filling the disk to the last byte leaves the metadata and combine steps, which
    // write a temporary copy per file, nowhere to work — the same reserve the ordinary check keeps.
    @Test
    fun theProcessingReserveIsKeptFree() {
        val archives = listOf(archive("part1.zip", 4 * gb))

        assertFalse(planLowSpaceImport(archives, availableBytes = 4 * gb, reserveBytes = gb).fits)
        assertTrue(planLowSpaceImport(archives, availableBytes = 5 * gb, reserveBytes = gb).fits)
    }

    @Test
    fun nothingToImportIsNotAnOffer() {
        assertFalse(planLowSpaceImport(emptyList(), availableBytes = gb, reserveBytes = gb).fits)
    }
}
