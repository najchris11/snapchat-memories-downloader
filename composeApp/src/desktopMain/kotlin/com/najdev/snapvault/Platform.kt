package com.najdev.snapvault

import kotlinx.coroutines.runInterruptible
import java.awt.Desktop
import java.net.URI

actual val isAndroidBuild: Boolean = false

actual suspend fun <T> runInterruptibleCompat(block: () -> T): T =
    runInterruptible(block = block)

actual fun openUrl(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            return
        }
        // Headless-ish desktops and most Linux setups without the AWT Desktop integration.
        val os = System.getProperty("os.name").lowercase()
        val command = when {
            os.contains("mac") -> arrayOf("open", url)
            os.contains("win") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> arrayOf("xdg-open", url)
        }
        Runtime.getRuntime().exec(command)
    }
}

actual fun binaryInstallHint(): String = when (BinaryExtractor.getPlatform()) {
    "darwin-arm64", "darwin-x64" -> """
        Bundled binaries: run scripts/prepare-runtime-macos.sh to download and package both tools automatically.
        Manual install (requires Homebrew): brew install exiftool ffmpeg
    """.trimIndent()

    "linux-x64" -> """
        FFmpeg is not bundled on Linux — install it with your package manager:
            sudo apt install ffmpeg   (Debian/Ubuntu)
            sudo dnf install ffmpeg   (Fedora/RHEL)
        ExifTool is bundled and runs via Perl. If ExifTool shows missing, ensure Perl is installed (sudo apt install perl), or install the system package: sudo apt install libimage-exiftool-perl / sudo dnf install perl-Image-ExifTool
    """.trimIndent()

    "windows-x64" -> """
        Bundled binaries: run scripts\prepare-runtime-windows.ps1 in PowerShell to download and package both tools automatically.
        Manual install: download exiftool.exe from exiftool.org and ffmpeg.exe from ffmpeg.org, then add both to your PATH.
    """.trimIndent()

    else -> """
        No bundled binaries available for this platform.
        Install ExifTool and FFmpeg manually and ensure both are on your PATH.
        ExifTool: https://exiftool.org    FFmpeg: https://ffmpeg.org/download.html
    """.trimIndent()
}
