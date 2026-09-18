package com.najdev.snapvault

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Installs the ffmpeg/exiftool that ship inside the app into a per-user cache.
 *
 * The cache used to be a flat `~/.snapvault/bin/<tool>`, and anything executable found there
 * was used forever (D15). Updating SnapVault — including to ship a fixed or security-patched
 * tool — changed nothing for anyone who had run an earlier version, and an extraction
 * interrupted partway looked installed as soon as its executable existed.
 *
 * Now each tool installs into `<base>/<tool>/<version>/`, where the version is the SHA-256 of
 * the bundled archive, so a different archive is a different directory. Extraction happens in
 * a staging directory, finishes by writing a marker, and is renamed into place in one step; a
 * directory without the marker is not an installation. Once a version is installed, older
 * ones are removed.
 */
internal class ToolInstaller(
    private val baseDir: File,
    private val platform: String,
    private val openResource: (String) -> InputStream?,
) {
    fun install(commandName: String): File? {
        // An unreadable bundled archive means no bundled tool, not a crash in whatever asked.
        val archive = try {
            openResource("/bin/$platform/$commandName.zip")?.use { it.readBytes() }
        } catch (_: IOException) {
            null
        } ?: return null
        val exeName = if (platform.startsWith("windows")) "$commandName.exe" else commandName
        val toolDir = File(baseDir, commandName)
        val versionDir = File(toolDir, sha256(archive).take(VERSION_LENGTH))

        if (isComplete(versionDir, exeName)) return File(versionDir, exeName)

        // Anything at this path without the marker is an earlier attempt that never finished.
        versionDir.deleteRecursively()
        // Pre-D15 installs left a flat executable at exactly this path — a file where the
        // versioned layout needs a directory. It is disposable cache content, never user data.
        if (toolDir.isFile) toolDir.delete()
        toolDir.mkdirs()
        val staging = File(toolDir, "$STAGING_PREFIX${System.nanoTime()}")
        try {
            if (!extract(archive, staging)) return null
            val installRoot = installRootOf(staging, exeName) ?: return null
            if (!platform.startsWith("windows")) {
                installRoot.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true, false) }
            }
            File(installRoot, COMPLETE_MARKER).writeText(sha256(archive))

            // Another process may have installed the same version while this one extracted.
            if (!installRoot.renameTo(versionDir) && !isComplete(versionDir, exeName)) return null
        } finally {
            staging.deleteRecursively()
        }

        removeOtherVersions(toolDir, keep = versionDir)
        return File(versionDir, exeName)
    }

    // The directory holding the executable: the archive root, or the one folder an archive
    // wraps its contents in. The committed Windows archives are the second kind — made by
    // zipping a folder, with Finder's __MACOSX and .DS_Store alongside — and requiring the root
    // meant bundled tools never installed on Windows at all. Anything else is ambiguous, and
    // nothing is installed.
    private fun installRootOf(extracted: File, exeName: String): File? {
        if (File(extracted, exeName).isFile) return extracted
        val entries = extracted.listFiles()?.filterNot { it.name == "__MACOSX" || it.name.startsWith(".") }.orEmpty()
        val folder = entries.singleOrNull()?.takeIf { it.isDirectory } ?: return null
        return folder.takeIf { File(it, exeName).isFile }
    }

    private fun isComplete(versionDir: File, exeName: String): Boolean =
        File(versionDir, COMPLETE_MARKER).isFile && File(versionDir, exeName).isFile

    private fun extract(archive: ByteArray, destDir: File): Boolean {
        val root = destDir.canonicalFile
        return try {
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(root, entry.name).canonicalFile
                    // Containment: an entry naming a path outside the install directory is a
                    // broken or tampered archive, and none of it is installed.
                    if (!target.path.startsWith(root.path + File.separator) && target != root) return false
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    // Older versions, and staging directories abandoned by an earlier crash. A recent staging
    // directory is left alone: it may be another SnapVault window extracting right now, and
    // deleting it out from under that window would make its tool look missing.
    private fun removeOtherVersions(toolDir: File, keep: File) {
        val abandonedBefore = System.currentTimeMillis() - ABANDONED_STAGING_AGE_MS
        toolDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory || dir == keep) return@forEach
            val staging = dir.name.startsWith(STAGING_PREFIX)
            if (!staging || dir.lastModified() < abandonedBefore) dir.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val COMPLETE_MARKER = ".installed"
        const val STAGING_PREFIX = ".staging-"
        const val VERSION_LENGTH = 16
        const val ABANDONED_STAGING_AGE_MS = 60 * 60 * 1000L
    }
}
