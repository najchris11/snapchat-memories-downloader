package com.najdev.snapvault

actual val isAndroidBuild: Boolean = false

actual fun nowIsoString(): String = java.time.Instant.now().toString().take(19) + "Z"

actual fun binaryInstallHint(): String = when (BinaryExtractor.getPlatform()) {
    "darwin-arm64", "darwin-x64" -> """
        Bundled binaries: run scripts/prepare-runtime-macos.sh to download and package both tools automatically.
        Manual install (requires Homebrew): brew install exiftool ffmpeg
    """.trimIndent()

    "linux-x64" -> """
        Bundled binaries: run scripts/prepare-runtime-linux.sh to download and package both tools automatically.
        Note: ExifTool runs via Perl (a Perl wrapper is bundled). If ExifTool still shows missing, ensure Perl is installed: sudo apt install perl  or  sudo dnf install perl
        Manual install: sudo apt install libimage-exiftool-perl ffmpeg  (Debian/Ubuntu)
                        sudo dnf install perl-Image-ExifTool ffmpeg      (Fedora/RHEL)
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
