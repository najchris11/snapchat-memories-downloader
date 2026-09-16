package com.najdev.snapvault

import com.najdev.snapvault.model.FileMeta
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Favorites are the only user-owned data SnapVault stores: everything else in
 * `vault_index.json` is a fact the pipeline can recompute by re-processing. That asymmetry is
 * what makes these tests worth having — a lost `hasGps` costs a re-run, a lost favorite is
 * gone.
 */
class VaultIndexTest {

    private lateinit var dir: File
    private val fs = FileSystem.SYSTEM

    @BeforeTest
    fun setUp() {
        dir = File.createTempFile("vault-index", "").apply { delete(); mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun writeRaw(json: String) = File(dir, VaultIndex.FILE_NAME).writeText(json)

    // The silent-data-loss case. FileMeta had no defaults on either field, and
    // kotlinx.serialization requires a value for every parameter without one — so a new field
    // added without a default makes every *existing* index fail to parse. That parse sits
    // inside runCatching { … }.getOrDefault(emptyMap()), so it would not surface as an error:
    // the Library would quietly report every item as having no GPS and no overlay.
    @Test
    fun anIndexWrittenBeforeFavoritesExistedStillParses() {
        writeRaw("""{"memory.jpg":{"hasGps":true,"hasOverlay":true}}""")

        val index = VaultIndex.read(fs, dir.absolutePath)

        assertEquals(
            FileMeta(hasGps = true, hasOverlay = true, favorited = false),
            index["memory.jpg"],
            "an index from a previous version must still parse, and must not lose its facts",
        )
    }

    // ── D05: a damaged index is recoverable data, not an empty one ──────────
    //
    // read() folds missing, unreadable, malformed and unsupported into the same empty map.
    // Every mutator then wrote that reduced state back over the original, so one favorite
    // toggle against a corrupt index replaced a user's whole favorite set with a single
    // entry — and reset deleted it outright. The bytes may well be recoverable by hand;
    // they are not recoverable once overwritten.

    @Test
    fun aDamagedIndexIsNotReplacedByAFavoriteWrite() = runBlocking {
        writeRaw("{damaged-but-recoverable-user-data")
        val original = File(dir, VaultIndex.FILE_NAME).readBytes()

        assertFailsWith<VaultIndexUnreadableException> {
            VaultIndex.setFavorite(fs, dir.absolutePath, "new.jpg", true)
        }

        assertEquals(
            original.toList(),
            File(dir, VaultIndex.FILE_NAME).readBytes().toList(),
            "the damaged bytes must survive untouched — they may hold every favorite the user has",
        )
    }

    @Test
    fun aDamagedIndexIsNotReplacedByAPipelineWrite() = runBlocking {
        writeRaw("{damaged-but-recoverable-user-data")
        val original = File(dir, VaultIndex.FILE_NAME).readBytes()

        assertFailsWith<VaultIndexUnreadableException> {
            VaultIndex.writeMerging(
                fs, dir.absolutePath,
                mapOf("fresh.jpg" to FileMeta(hasGps = false, hasOverlay = false)),
            )
        }

        assertEquals(original.toList(), File(dir, VaultIndex.FILE_NAME).readBytes().toList())
    }

    @Test
    fun aDamagedIndexIsNotDeletedByReset() = runBlocking {
        writeRaw("{damaged-but-recoverable-user-data")
        val original = File(dir, VaultIndex.FILE_NAME).readBytes()

        assertFailsWith<VaultIndexUnreadableException> {
            VaultIndex.resetKeepingFavorites(fs, dir.absolutePath)
        }

        assertTrue(File(dir, VaultIndex.FILE_NAME).exists(), "reset must not delete what it could not read")
        assertEquals(original.toList(), File(dir, VaultIndex.FILE_NAME).readBytes().toList())
    }

    // The counterpart, and the reason this is not just "throw on empty": a folder with no
    // index yet is the ordinary first-run state. Failing closed there would make the very
    // first favorite impossible.
    @Test
    fun aMissingIndexIsStillAnEmptyOneAndCanBeWritten() = runBlocking {
        assertTrue(!File(dir, VaultIndex.FILE_NAME).exists(), "setup: no index yet")

        VaultIndex.setFavorite(fs, dir.absolutePath, "first.jpg", true)

        assertEquals(true, VaultIndex.read(fs, dir.absolutePath)["first.jpg"]?.favorited)
    }

    // read() itself must stay total. It is called from scanMediaFiles on every Library scan,
    // which is not a coroutine and has no way to handle a throw; a damaged index there means
    // the facts are unknown, which is what an empty map already says. The fix belongs in the
    // mutators, which are the ones that destroy it.
    @Test
    fun readStillReportsADamagedIndexAsEmptyRatherThanThrowing() {
        writeRaw("{damaged-but-recoverable-user-data")

        assertEquals(emptyMap(), VaultIndex.read(fs, dir.absolutePath))
    }

    @Test
    fun aFavoriteSurvivesARoundTrip() = runBlocking {
            VaultIndex.setFavorite(fs, dir.absolutePath, "memory.jpg", true)

            assertEquals(true, VaultIndex.read(fs, dir.absolutePath)["memory.jpg"]?.favorited)

            VaultIndex.setFavorite(fs, dir.absolutePath, "memory.jpg", false)
            assertEquals(false, VaultIndex.read(fs, dir.absolutePath)["memory.jpg"]?.favorited)
    }

    @Test
    fun favoritingDoesNotDisturbTheFactsThePipelineOwns() = runBlocking {
            writeRaw("""{"memory.jpg":{"hasGps":true,"hasOverlay":true}}""")

            VaultIndex.setFavorite(fs, dir.absolutePath, "memory.jpg", true)

            assertEquals(
                FileMeta(hasGps = true, hasOverlay = true, favorited = true),
                VaultIndex.read(fs, dir.absolutePath)["memory.jpg"],
            )
    }

    // The one the plan predicted would actually break. The pipeline builds its entries from
    // scratch — FileMeta(hasGps = …, hasOverlay = …) at five sites — so a new field with a
    // default would be silently reset to it on every run, wiping every favorite.
    @Test
    fun aPipelineRewriteKeepsFavorites() {
        writeRaw(
            """{"kept.jpg":{"hasGps":false,"hasOverlay":false,"favorited":true},""" +
                """"plain.jpg":{"hasGps":false,"hasOverlay":false,"favorited":false}}"""
        )
        val onDisk = VaultIndex.read(fs, dir.absolutePath)

        // What a run produces: freshly built, favorites nowhere in sight.
        val fromPipeline = mapOf(
            "kept.jpg" to FileMeta(hasGps = true, hasOverlay = false),
            "plain.jpg" to FileMeta(hasGps = true, hasOverlay = true),
            "new.jpg" to FileMeta(hasGps = true, hasOverlay = false),
        )

        val merged = VaultIndex.mergeUserFields(onDisk = onDisk, pipeline = fromPipeline)

        assertEquals(true, merged["kept.jpg"]?.favorited, "the run wiped a favorite")
        assertEquals(true, merged["kept.jpg"]?.hasGps, "the run's own facts must still win")
        assertEquals(false, merged["plain.jpg"]?.favorited)
        assertEquals(false, merged["new.jpg"]?.favorited, "a file the run just found cannot be a favorite")
        assertEquals(setOf("kept.jpg", "plain.jpg", "new.jpg"), merged.keys)
    }

    // A run's map is seeded from the index and added to as it goes, so an entry it does not
    // mention is one it never looked at. Rewriting `pipeline` rather than unioning onto
    // `onDisk` dropped exactly the entries written to disk after the run loaded its copy.
    @Test
    fun anEntryTheRunNeverTouchedIsNotDroppedByItsWrite() {
        val onDisk = mapOf("untouched.jpg" to FileMeta(hasGps = true, hasOverlay = false, favorited = true))

        val merged = VaultIndex.mergeUserFields(
            onDisk = onDisk,
            pipeline = mapOf("processed.jpg" to FileMeta(hasGps = true, hasOverlay = true)),
        )

        assertEquals(setOf("untouched.jpg", "processed.jpg"), merged.keys)
        assertEquals(onDisk["untouched.jpg"], merged["untouched.jpg"], "an untouched entry must survive intact")
    }

    // A favorite set while a run is in flight is not in that run's in-memory map, so the
    // merge has to read from disk at write time rather than trusting the copy loaded at the
    // start of the run.
    @Test
    fun aFavoriteSetDuringARunSurvivesThatRunsWrite() = runBlocking {
            val pipelineStarted = VaultIndex.read(fs, dir.absolutePath)
            assertTrue(pipelineStarted.isEmpty())

            // …the user favorites something while the run is going…
            VaultIndex.setFavorite(fs, dir.absolutePath, "memory.jpg", true)

            // …and the run finishes and writes its own map.
            VaultIndex.writeMerging(
                fs,
                dir.absolutePath,
                mapOf("memory.jpg" to FileMeta(hasGps = true, hasOverlay = false)),
            )

            assertEquals(
                FileMeta(hasGps = true, hasOverlay = false, favorited = true),
                VaultIndex.read(fs, dir.absolutePath)["memory.jpg"],
            )
    }

    @Test
    fun aMissingIndexReadsAsEmptyRatherThanThrowing() {
        assertEquals(emptyMap(), VaultIndex.read(fs, dir.absolutePath))
    }

    @Test
    fun aCorruptIndexReadsAsEmptyRatherThanThrowing() {
        writeRaw("{ this is not json")
        assertEquals(emptyMap(), VaultIndex.read(fs, dir.absolutePath))
    }

    // Reset exists to make the next run re-process everything. It must not take favorites
    // with it: those are not derivable from the export and cannot be recovered by re-running.
    @Test
    fun resetClearsProcessingStateButKeepsFavorites() = runBlocking {
            writeRaw(
                """{"kept.jpg":{"hasGps":true,"hasOverlay":true,"favorited":true},""" +
                    """"plain.jpg":{"hasGps":true,"hasOverlay":true,"favorited":false}}"""
            )

            VaultIndex.resetKeepingFavorites(fs, dir.absolutePath)

            val after = VaultIndex.read(fs, dir.absolutePath)
            assertEquals(setOf("kept.jpg"), after.keys, "only favorites survive a reset")
            assertEquals(FileMeta(hasGps = false, hasOverlay = false, favorited = true), after["kept.jpg"])
    }

    @Test
    fun resetWithNoFavoritesLeavesNoIndexBehind() = runBlocking {
            writeRaw("""{"plain.jpg":{"hasGps":true,"hasOverlay":true,"favorited":false}}""")

            VaultIndex.resetKeepingFavorites(fs, dir.absolutePath)

            assertEquals(false, fs.exists("${dir.absolutePath}/${VaultIndex.FILE_NAME}".toPath()))
    }

    // Older indexes are written without the field. Kotlin's default-value encoding would omit
    // `favorited: false` entirely, which is fine to read back but makes the file harder to
    // inspect by hand — and this file is the only place a favorite lives.
    @Test
    fun favoritesAreWrittenExplicitlyRatherThanOmittedAsADefault() = runBlocking {
            VaultIndex.setFavorite(fs, dir.absolutePath, "memory.jpg", false)

            val raw = File(dir, VaultIndex.FILE_NAME).readText()
            assertTrue("favorited" in raw, "the field should be visible in the file: $raw")
            // Still has to be readable by the strict-ish decoder the app uses.
            assertEquals(false, Json.decodeFromString<Map<String, FileMeta>>(raw)["memory.jpg"]?.favorited)
    }

    // The Library identifies an item by its absolute path; the index is keyed by file name,
    // because that is what the scanner looks entries up by. Deriving one from the other with
    // substringAfterLast('/') worked only on POSIX: a Windows absolute path contains no
    // forward slash at all, so the *whole path* became the key. The optimistic heart appeared
    // and then vanished on the next scan, which found nothing under the file name.
    @Test
    fun theIndexKeyIsTheFileNameOnWindowsPathsToo() {
        assertEquals("memory.jpg", VaultIndex.keyOf("""C:\\vault\\2026\\memory.jpg"""))
        assertEquals("memory.jpg", VaultIndex.keyOf("/home/someone/SnapVault/memory.jpg"))
        // A UNC path, and a path that mixes separators the way a hand-built one can.
        assertEquals("memory.jpg", VaultIndex.keyOf("""\\\\server\\share\\memory.jpg"""))
        assertEquals("memory.jpg", VaultIndex.keyOf("""C:/vault\\memory.jpg"""))
        // Already a bare name.
        assertEquals("memory.jpg", VaultIndex.keyOf("memory.jpg"))
    }

    @Test
    fun aFavoriteSetFromAWindowsStylePathIsFoundUnderTheFileName() = runBlocking {
            VaultIndex.setFavorite(
                fs,
                dir.absolutePath,
                VaultIndex.keyOf("""C:\\vault\\memory.jpg"""),
                true,
            )

            assertEquals(
                true,
                VaultIndex.read(fs, dir.absolutePath)["memory.jpg"]?.favorited,
                "the scanner looks the entry up by file name, so that has to be the key",
            )
    }
}
