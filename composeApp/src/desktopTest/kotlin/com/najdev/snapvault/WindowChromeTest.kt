package com.najdev.snapvault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D21: the window SnapVault asks the OS for.
 *
 * It was borderless on every platform, with text glyphs for close/minimize/maximize and a
 * content area that dragged the window. macOS window managers read a window with no native
 * fullscreen button as a dialog — AeroSpace floats those rather than tiling them, which is what
 * the user reported — and the app also had no native fullscreen, no Mission Control behaviour
 * and no standard accessibility contract there.
 */
class WindowChromeTest {

    @Test
    fun macOsKeepsTheNativeTitleBarAndItsControls() {
        val chrome = windowChromeFor("Mac OS X")

        assertFalse(chrome.undecorated, "a borderless window has no native fullscreen button")
        assertFalse(chrome.customWindowControls, "the native traffic lights are the controls")
        assertFalse(chrome.dragFromContent, "the native title bar moves the window")
    }

    @Test
    fun windowsAndLinuxKeepTheCustomTitleBar() {
        listOf("Windows 11", "Linux").forEach { os ->
            val chrome = windowChromeFor(os)

            assertTrue(chrome.undecorated, os)
            assertTrue(chrome.customWindowControls, os)
            assertTrue(chrome.dragFromContent, os)
        }
    }

    // The values above only matter if the window is built from them. Main.kt is a top-level
    // `application {}` with no seam a test can call, so this reads it.
    @Test
    fun theWindowIsBuiltFromTheChromeRatherThanFixedValues() {
        val repoRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val main = File(repoRoot, "composeApp/src/desktopMain/kotlin/com/najdev/snapvault/Main.kt").readText()

        assertEquals(
            emptyList(),
            listOf("undecorated = true", "showWindowControls = true").filter { it in main },
            "Main.kt hardcodes window chrome instead of using windowChromeFor",
        )
        assertTrue("windowChromeFor(" in main, "Main.kt does not consult windowChromeFor")
    }
}
