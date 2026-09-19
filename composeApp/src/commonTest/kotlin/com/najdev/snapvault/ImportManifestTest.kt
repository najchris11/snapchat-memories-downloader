package com.najdev.snapvault

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * D20: the durable record of which source archives were imported, and which of them were
 * deleted afterwards. It is written before an archive is deleted, so a crash cannot leave a
 * deletion that nothing accounts for, and it outlives the log, which is a session's worth.
 */
class ImportManifestTest {

    private fun fs() = FakeFileSystem().apply { createDirectories("/out".toPath()) }

    private fun archive(name: String, deleted: Boolean = true, keptBecause: String? = null) =
        ImportedArchive(name = name, sizeBytes = 1024, memories = 3, deleted = deleted, keptBecause = keptBecause)

    @Test
    fun anArchiveIsRecordedAndReadBack() {
        val fs = fs()

        ImportManifest.record(fs, "/out", archive("part1.zip"))

        assertEquals(listOf(archive("part1.zip")), ImportManifest.read(fs, "/out"))
    }

    @Test
    fun recordingTheSameArchiveAgainReplacesItRatherThanRepeatingIt() {
        val fs = fs()

        ImportManifest.record(fs, "/out", archive("part1.zip", deleted = false, keptBecause = "2 file(s) missing"))
        ImportManifest.record(fs, "/out", archive("part1.zip", deleted = true))
        ImportManifest.record(fs, "/out", archive("part2.zip"))

        val recorded = ImportManifest.read(fs, "/out")
        assertEquals(listOf("part1.zip", "part2.zip"), recorded.map { it.name })
        assertTrue(recorded.first().deleted, "the later outcome is the one that stands")
    }

    @Test
    fun noManifestYetReadsAsNothingImported() {
        assertEquals(emptyList(), ImportManifest.read(fs(), "/out"))
    }

    // Overwriting it would erase the record of archives already deleted — the one thing that
    // cannot be recovered by importing again. Refusing means the caller keeps the archive.
    @Test
    fun aManifestThatCannotBeReadIsNotOverwritten() {
        val fs = fs()
        val path = "/out/${ImportManifest.DIR_NAME}/${ImportManifest.FILE_NAME}".toPath()
        fs.createDirectories(path.parent!!)
        fs.write(path) { writeUtf8("{ this is not the manifest") }

        assertFailsWith<ImportManifestUnreadableException> { ImportManifest.record(fs, "/out", archive("part1.zip")) }

        assertEquals("{ this is not the manifest", fs.read(path) { readUtf8() })
    }
}
