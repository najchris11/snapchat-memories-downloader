package com.najdev.snapvault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Packaging hygiene contracts for `composeApp/build.gradle.kts`.
 *
 * Two silent installer regressions motivated these checks:
 * 1. `desktopProcessResources` copied every OS/arch subdirectory under `resources/bin/` into
 *    every native installer (~67–115 MB of foreign-OS executables per `.dmg`/`.msi`/`.deb`),
 *    while `:composeApp:desktopTest` legitimately needs all committed archives on the test
 *    classpath so `ShippedToolBundlesTest` and `ShippedToolManifestTest` can verify them on
 *    any runner.
 * 2. `nativeDistributions` lacked Windows `upgradeUuid`, `perUserInstall`, and shortcut
 *    settings (forcing UAC elevation and breaking in-place MSI upgrades) as well as macOS
 *    `bundleID` and Linux `.deb` package metadata.
 */
class NativeDistributionConfigTest {

    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
    }

    private val buildScript: String by lazy {
        File(repoRoot, "composeApp/build.gradle.kts").readText()
    }

    @Test
    fun windowsInstallerConfiguresUpgradeUuidPerUserInstallAndShortcuts() {
        val uuidMatch = Regex("""upgradeUuid\s*=\s*"([0-9a-fA-F-]{36})"""").find(buildScript)
        assertTrue(uuidMatch != null, "windows {} must declare a fixed 36-char upgradeUuid for MSI upgrades")
        val uuid = uuidMatch.groupValues[1]
        assertTrue(
            Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""", RegexOption.IGNORE_CASE)
                .matches(uuid),
            "upgradeUuid '$uuid' is not a valid RFC 4122 UUID",
        )
        assertTrue("perUserInstall = true" in buildScript, "windows {} should set perUserInstall = true")
        assertTrue("dirChooser = true" in buildScript, "windows {} should set dirChooser = true")
        assertTrue("shortcut = true" in buildScript, "nativeDistributions should enable desktop/menu shortcuts")
        assertTrue("""menuGroup = "SnapVault"""" in buildScript, "windows {} should set menuGroup = \"SnapVault\"")
    }

    @Test
    fun macOsAndLinuxInstallersDeclareBundleIdAndDebMetadata() {
        assertTrue(
            """bundleID = "com.najdev.snapvault"""" in buildScript,
            "macOS {} should set bundleID = \"com.najdev.snapvault\"",
        )
        assertTrue("debMaintainer =" in buildScript, "linux {} should declare debMaintainer")
        assertTrue("appCategory =" in buildScript, "linux {} should declare appCategory")
    }

    @Test
    fun desktopProcessResourcesTracksBundleTargetPlatformInputAndFiltersForeignBins() {
        assertTrue(
            """inputs.property("bundleTargetPlatform"""" in buildScript,
            "desktopProcessResources must register bundleTargetPlatform as a Gradle task input " +
                "so running packageReleaseDistributionForCurrentOS after desktopTest invalidates " +
                "the unfiltered test resource output",
        )
        assertTrue(
            """exclude("bin/${'$'}platform/**")""" in buildScript ||
                """exclude("bin/${'$'}otherPlatform/**")""" in buildScript,
            "desktopProcessResources must exclude foreign bin/<platform>/** directories when packaging",
        )
    }

    @Test
    fun desktopTestClasspathStillRetainsAllCommittedPlatformArchives() {
        val committedArchives = listOf(
            "/bin/manifest.json",
            "/bin/darwin-arm64/ffmpeg.zip",
            "/bin/darwin-arm64/exiftool.zip",
            "/bin/darwin-x64/ffmpeg.zip",
            "/bin/darwin-x64/exiftool.zip",
            "/bin/windows-x64/ffmpeg.zip",
            "/bin/windows-x64/exiftool.zip",
        )
        val missing = committedArchives.filter { path -> javaClass.getResource(path) == null }
        assertEquals(emptyList(), missing, "desktopTest classpath lost committed tool resources")
    }
}
