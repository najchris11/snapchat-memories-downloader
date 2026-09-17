package com.najdev.snapvault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D15: extracted ffmpeg/exiftool. Every test uses a temporary base directory — never the real
 * `~/.snapvault/bin`.
 */
class ToolInstallerTest {

    private lateinit var root: File
    private lateinit var base: File

    @BeforeTest
    fun setUp() {
        root = createTempDirectory("snapvault-tools").toFile()
        base = File(root, "bin").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zos ->
            entries.forEach { (name, text) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray())
                zos.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun installer(zip: ByteArray) =
        ToolInstaller(base, "linux-x64") { path -> if (path == "/bin/linux-x64/exiftool.zip") ByteArrayInputStream(zip) else null }

    // D15: an executable already sitting in the cache was used forever. Updating SnapVault —
    // including to ship a fixed, or security-patched, tool — changed nothing for anyone who had
    // run an earlier version, because the old extraction was found first.
    @Test
    fun anOlderCachedToolIsReplacedByTheBundledVersion() {
        val v1 = installer(zipOf("exiftool" to "exiftool v1", "exiftool-dist/lib" to "lib v1")).install("exiftool")
        assertNotNull(v1)
        assertEquals("exiftool v1", v1.readText())

        val v2 = installer(zipOf("exiftool" to "exiftool v2", "exiftool-dist/lib" to "lib v2")).install("exiftool")

        assertNotNull(v2)
        assertEquals("exiftool v2", v2.readText(), "the cached older tool was used instead of the bundled one")
        assertEquals("lib v2", File(v2.parentFile, "exiftool-dist/lib").readText())
        assertFalse(v1.exists() && v1.readText() == "exiftool v1", "the superseded version should be cleaned up")
    }

    // An extraction interrupted after the executable was written but before its companion files
    // left something that looked installed. On Windows every file "can execute", so nothing
    // stood between that half-install and being run. Simulated here as exactly what such an
    // interruption leaves: the executable, part of what it needs, and no record of finishing.
    @Test
    fun anInterruptedInstallIsNotTreatedAsInstalled() {
        val zip = zipOf("exiftool" to "exiftool v1", "exiftool-dist/lib" to "lib v1")
        val installed = installer(zip).install("exiftool")
        assertNotNull(installed)
        File(installed.parentFile, "exiftool-dist/lib").delete()
        base.walkTopDown().filter { it.name == ".installed" }.forEach { it.delete() }
        // As Windows sees it: whatever was left behind is executable.
        base.walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true, false) }

        val tool = installer(zip).install("exiftool")

        assertNotNull(tool)
        assertEquals(
            "lib v1",
            File(tool.parentFile, "exiftool-dist/lib").takeIf { it.isFile }?.readText(),
            "a half-installed tool was used without the files it needs",
        )
    }

    @Test
    fun anUnreadableBundledArchiveMeansNoToolRatherThanACrash() {
        val zip = zipOf("exiftool" to "exiftool v1")
        val broken = ToolInstaller(base, "linux-x64") { ByteArrayInputStream(zip).failingAfter(10) }

        assertNull(broken.install("exiftool"))
        assertTrue(base.walkTopDown().none { it.isFile }, "nothing may be left behind")
    }

    // The resource ZIP ships with the app, so this is not a Snapchat-export exploit — but an
    // extractor that writes wherever an entry name points is one tampered build away from one.
    @Test
    fun entriesCannotEscapeTheInstallDirectory() {
        val escape = zipOf("exiftool" to "tool", "../../escaped.txt" to "outside")

        val tool = installer(escape).install("exiftool")

        assertNull(tool, "an archive trying to write outside its directory must not install")
        assertFalse(root.walkTopDown().any { it.name == "escaped.txt" }, "a file was written outside the install directory")
        assertFalse(File(root.parentFile, "escaped.txt").exists())
    }

    @Test
    fun theSameVersionIsReusedNotReextracted() {
        val zip = zipOf("exiftool" to "exiftool v1")
        val first = installer(zip).install("exiftool")
        assertNotNull(first)
        first.setLastModified(1_000_000L)

        val second = installer(zip).install("exiftool")

        assertEquals(first.absolutePath, second?.absolutePath)
        assertEquals(1_000_000L, second?.lastModified(), "the installed tool was extracted again")
        assertTrue(second!!.canExecute())
    }

    private fun InputStream.failingAfter(bytes: Int): InputStream = object : FilterInputStream(this) {
        private var remaining = bytes
        override fun read(): Int {
            if (remaining-- <= 0) throw IOException("disk full")
            return super.read()
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) throw IOException("disk full")
            val n = super.read(b, off, minOf(len, remaining))
            if (n > 0) remaining -= n
            return n
        }
    }

    // An archive made by zipping a folder wraps everything in that folder, and one zipped in
    // Finder adds __MACOSX beside it. The committed Windows archives are both; see
    // ShippedToolBundlesTest for the archives themselves.
    @Test
    fun anArchiveThatWrapsTheToolInOneFolderInstallsFromThatFolder() {
        val zip = zipOf(
            "exiftool-13/exiftool" to "#!/bin/sh",
            "exiftool-13/lib/Image.pm" to "perl",
            "__MACOSX/exiftool-13/._exiftool" to "resource fork",
        )

        val exe = assertNotNull(installer(zip).install("exiftool"))

        assertEquals("exiftool", exe.name)
        assertTrue(File(exe.parentFile, "lib/Image.pm").isFile, "the folder's other files moved with it")
    }

    // Two folders could each be the tool; guessing would install whichever listed first.
    @Test
    fun anArchiveWithMoreThanOneCandidateFolderInstallsNothing() {
        val zip = zipOf("a/exiftool" to "one", "b/exiftool" to "two")

        assertNull(installer(zip).install("exiftool"))
    }
}
