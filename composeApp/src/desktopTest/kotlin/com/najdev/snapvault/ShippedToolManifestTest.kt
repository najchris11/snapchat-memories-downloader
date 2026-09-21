package com.najdev.snapvault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * D17: which FFmpeg and ExifTool builds ship, where they came from, and under what license,
 * recorded in `bin/manifest.json` — and kept true. Nothing recorded any of it: the fetch
 * scripts pulled whatever a mutable "latest" URL served, and a replaced binary looked like
 * every other commit.
 */
class ShippedToolManifestTest {

    private val manifest: List<JsonObject> by lazy {
        val text = javaClass.getResourceAsStream("/bin/manifest.json")?.use { it.readBytes().decodeToString() }
            ?: fail("bin/manifest.json is not on the classpath")
        Json.parseToJsonElement(text).jsonObject.getValue("bundles").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // The same list ShippedToolBundlesTest installs: everything committed under bin/.
    private val committedArchives = listOf(
        "darwin-arm64/ffmpeg.zip", "darwin-arm64/exiftool.zip",
        "darwin-x64/ffmpeg.zip", "darwin-x64/exiftool.zip",
        "windows-x64/ffmpeg.zip", "windows-x64/exiftool.zip",
    )

    @Test
    fun everyCommittedArchiveHasAnEntry() {
        val recorded = manifest.mapNotNull { it.string("archive") }.toSet()

        assertEquals(emptyList(), committedArchives.filterNot { it in recorded }, "archives with no manifest entry")
    }

    @Test
    fun everyEntryRecordsWhereItCameFromAndItsLicense() {
        val incomplete = manifest.flatMap { entry ->
            listOf("platform", "tool", "version", "upstreamUrl", "upstreamSha256", "license", "source")
                .filter { entry.string(it).isNullOrBlank() }
                .map { "${entry.string("platform")}/${entry.string("tool")}: no $it" }
        }
        assertTrue(incomplete.isEmpty(), incomplete.joinToString("\n"))
        // A mutable "latest" link is exactly what this replaces.
        val unpinned = manifest.filter { entry -> entry.string("upstreamUrl").orEmpty().let { "latest" in it || "getrelease" in it } }
        assertTrue(unpinned.isEmpty(), "unpinned upstream URLs: $unpinned")
    }

    // A replaced archive — or a re-zip of the same tool — fails here until its entry is updated.
    @Test
    fun archivesAndTheirExecutablesMatchTheRecordedHashes() {
        val mismatches = manifest.filter { it.string("archive") != null }.flatMap { entry ->
            val archive = entry.string("archive")!!
            val bytes = javaClass.getResourceAsStream("/bin/$archive")?.use { it.readBytes() }
                ?: return@flatMap listOf("$archive: not on the classpath")
            buildList {
                val actual = sha256(bytes)
                if (actual != entry.string("archiveSha256")) add("$archive: archive is $actual")
                entry.string("executableSha256")?.let { expected ->
                    val exe = executableIn(bytes, entry.string("executable")!!)
                    when {
                        exe == null -> add("$archive: no ${entry.string("executable")} inside")
                        sha256(exe) != expected -> add("$archive: ${entry.string("executable")} is ${sha256(exe)}")
                    }
                }
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    private fun executableIn(zip: ByteArray, name: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val path = entry.name
                val named = path == name || path.endsWith("/$name")
                if (named && !entry.isDirectory && !path.startsWith("__MACOSX/")) return zis.readBytes()
                entry = zis.nextEntry
            }
        }
        return null
    }

    // The fetch scripts verify each download against a hash of their own. If those drift from
    // the manifest, re-running a script either fails for no visible reason or installs a build
    // the manifest does not describe.
    @Test
    fun theFetchScriptsVerifyTheDownloadsTheManifestRecords() {
        val repoRoot = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
        fun script(platform: String) = java.io.File(
            repoRoot,
            when {
                platform.startsWith("darwin") -> "scripts/prepare-runtime-macos.sh"
                platform.startsWith("windows") -> "scripts/prepare-runtime-windows.ps1"
                else -> "scripts/prepare-runtime-linux.sh"
            },
        ).readText()

        val missing = manifest.filterNot { entry -> entry.string("upstreamSha256")!! in script(entry.string("platform")!!) }
            .map { "${it.string("platform")}/${it.string("tool")}: script does not verify ${it.string("upstreamSha256")}" }
        assertTrue(missing.isEmpty(), missing.joinToString("\n"))

        val mutable = listOf("getrelease", "release-essentials", "/latest")
        val unpinned = listOf("darwin", "windows", "linux").flatMap { platform ->
            mutable.filter { it in script(platform) }.map { "$platform script fetches a moving URL containing '$it'" }
        }
        assertTrue(unpinned.isEmpty(), unpinned.joinToString("\n"))
    }
}
