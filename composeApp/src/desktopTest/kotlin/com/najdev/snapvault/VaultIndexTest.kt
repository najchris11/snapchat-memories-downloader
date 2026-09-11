package com.najdev.snapvault

import com.najdev.snapvault.model.FileMeta
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Favourites are the only user-owned data SnapVault stores: everything else in
 * `vault_index.json` is a fact the pipeline can recompute by re-processing. That asymmetry is
 * what makes these tests worth having — a lost `hasGps` costs a re-run, a lost favourite is
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
    fun anIndexWrittenBeforeFavouritesExistedStillParses() {
        writeRaw("""{"memory.jpg":{"hasGps":true,"hasOverlay":true}}""")

        val index = VaultIndex.read(fs, dir.absolutePath)

        assertEquals(
            FileMeta(hasGps = true, hasOverlay = true, favorited = false),
            index["memory.jpg"],
            "an index from a previous version must still parse, and must not lose its facts",
        )
    }

    @Test
    fun aFavouriteSurvivesARoundTrip() {
        VaultIndex.setFavorite(fs, dir.absolutePath, "memory.jpg", true)

        assertEquals(true, VaultIndex.read(fs, dir.absolutePath)["memory.jpg"]?.favorited)

        VaultIndex.setFavorite(fs, dir.absolutePath, "memory.jpg", false)
        assertEquals(false, VaultIndex.read(fs, dir.absolutePath)["memory.jpg"]?.favorited)
    }

    @Test
    fun favouritingDoesNotDisturbTheFactsThePipelineOwns() {
        writeRaw("""{"memory.jpg":{"hasGps":true,"hasOverlay":true}}""")

        VaultIndex.setFavorite(fs, dir.absolutePath, "memory.jpg", true)

        assertEquals(
            FileMeta(hasGps = true, hasOverlay = true, favorited = true),
            VaultIndex.read(fs, dir.absolutePath)["memory.jpg"],
        )
    }

    // The one the plan predicted would actually break. The pipeline builds its entries from
    // scratch — FileMeta(hasGps = …, hasOverlay = …) at five sites — so a new field with a
    // default would be silently reset to it on every run, wiping every favourite.
    @Test
    fun aPipelineRewriteKeepsFavourites() {
        writeRaw(
            """{"kept.jpg":{"hasGps":false,"hasOverlay":false,"favorited":true},""" +
                """"plain.jpg":{"hasGps":false,"hasOverlay":false,"favorited":false}}"""
        )
        val onDisk = VaultIndex.read(fs, dir.absolutePath)

        // What a run produces: freshly built, favourites nowhere in sight.
        val fromPipeline = mapOf(
            "kept.jpg" to FileMeta(hasGps = true, hasOverlay = false),
            "plain.jpg" to FileMeta(hasGps = true, hasOverlay = true),
            "new.jpg" to FileMeta(hasGps = true, hasOverlay = false),
        )

        val merged = VaultIndex.mergeUserFields(onDisk = onDisk, pipeline = fromPipeline)

        assertEquals(true, merged["kept.jpg"]?.favorited, "the run wiped a favourite")
        assertEquals(true, merged["kept.jpg"]?.hasGps, "the run's own facts must still win")
        assertEquals(false, merged["plain.jpg"]?.favorited)
        assertEquals(false, merged["new.jpg"]?.favorited, "a file the run just found cannot be a favourite")
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

    // A favourite set while a run is in flight is not in that run's in-memory map, so the
    // merge has to read from disk at write time rather than trusting the copy loaded at the
    // start of the run.
    @Test
    fun aFavouriteSetDuringARunSurvivesThatRunsWrite() {
        val pipelineStarted = VaultIndex.read(fs, dir.absolutePath)
        assertTrue(pipelineStarted.isEmpty())

        // …the user favourites something while the run is going…
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

    // Reset exists to make the next run re-process everything. It must not take favourites
    // with it: those are not derivable from the export and cannot be recovered by re-running.
    @Test
    fun resetClearsProcessingStateButKeepsFavourites() {
        writeRaw(
            """{"kept.jpg":{"hasGps":true,"hasOverlay":true,"favorited":true},""" +
                """"plain.jpg":{"hasGps":true,"hasOverlay":true,"favorited":false}}"""
        )

        VaultIndex.resetKeepingFavorites(fs, dir.absolutePath)

        val after = VaultIndex.read(fs, dir.absolutePath)
        assertEquals(setOf("kept.jpg"), after.keys, "only favourites survive a reset")
        assertEquals(FileMeta(hasGps = false, hasOverlay = false, favorited = true), after["kept.jpg"])
    }

    @Test
    fun resetWithNoFavouritesLeavesNoIndexBehind() {
        writeRaw("""{"plain.jpg":{"hasGps":true,"hasOverlay":true,"favorited":false}}""")

        VaultIndex.resetKeepingFavorites(fs, dir.absolutePath)

        assertEquals(false, fs.exists("${dir.absolutePath}/${VaultIndex.FILE_NAME}".toPath()))
    }

    // Older indexes are written without the field. Kotlin's default-value encoding would omit
    // `favorited: false` entirely, which is fine to read back but makes the file harder to
    // inspect by hand — and this file is the only place a favourite lives.
    @Test
    fun favouritesAreWrittenExplicitlyRatherThanOmittedAsADefault() {
        VaultIndex.setFavorite(fs, dir.absolutePath, "memory.jpg", false)

        val raw = File(dir, VaultIndex.FILE_NAME).readText()
        assertTrue("favorited" in raw, "the field should be visible in the file: $raw")
        // Still has to be readable by the strict-ish decoder the app uses.
        assertEquals(false, Json.decodeFromString<Map<String, FileMeta>>(raw)["memory.jpg"]?.favorited)
    }
}
