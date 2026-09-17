package com.najdev.snapvault

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The tool archives the installers actually ship, installed the way the app installs them.
 *
 * ToolInstallerTest builds its archives by hand, so it could only ever confirm the layout it
 * assumed. The committed Windows archives did not have that layout — each executable sat inside
 * a folder, not at the archive root — and a Windows user without FFmpeg or ExifTool on PATH got
 * neither, on every release. Only reading the real resources catches that.
 */
class ShippedToolBundlesTest {

    private data class Bundle(val platform: String, val tool: String)

    // Committed to the repository and packaged by every release. Linux's exiftool archive is
    // built by CI and is only checked when present; Linux ships no FFmpeg.
    private val committed = listOf(
        Bundle("darwin-arm64", "ffmpeg"),
        Bundle("darwin-arm64", "exiftool"),
        Bundle("darwin-x64", "ffmpeg"),
        Bundle("darwin-x64", "exiftool"),
        Bundle("windows-x64", "ffmpeg"),
        Bundle("windows-x64", "exiftool"),
    )

    private fun resource(path: String) = javaClass.getResourceAsStream(path)

    @Test
    fun everyShippedArchiveInstallsItsExecutable() {
        val bundles = committed + listOfNotNull(
            Bundle("linux-x64", "exiftool").takeIf { resource("/bin/linux-x64/exiftool.zip") != null },
        )
        val base = createTempDirectory("snapvault-shipped-tools").toFile()
        try {
            val failures = bundles.mapNotNull { (platform, tool) ->
                val installed = ToolInstaller(File(base, platform), platform, ::resource).install(tool)
                val exeName = if (platform.startsWith("windows")) "$tool.exe" else tool
                when {
                    resource("/bin/$platform/$tool.zip") == null -> "$platform/$tool.zip is not on the classpath"
                    installed == null -> "$platform/$tool.zip installed nothing"
                    installed.name != exeName || !installed.isFile || installed.length() == 0L ->
                        "$platform/$tool.zip installed $installed, not a usable $exeName"
                    else -> null
                }
            }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        } finally {
            base.deleteRecursively()
        }
    }

    // Windows ExifTool is a launcher that runs the Perl in exiftool_files beside it; installed
    // on its own it starts and fails.
    @Test
    fun windowsExifToolKeepsItsPerlRuntimeBesideIt() {
        val base = createTempDirectory("snapvault-shipped-exiftool").toFile()
        try {
            val exe = assertNotNull(ToolInstaller(base, "windows-x64", ::resource).install("exiftool"))
            assertTrue(File(exe.parentFile, "exiftool_files/perl.exe").isFile, "no exiftool_files/perl.exe beside $exe")
        } finally {
            base.deleteRecursively()
        }
    }
}
