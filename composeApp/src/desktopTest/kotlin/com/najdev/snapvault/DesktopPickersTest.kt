package com.najdev.snapvault

import java.io.File
import javax.swing.JFileChooser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPickersTest {
    // AWT's FileDialog is the native GTK chooser on Linux. On a desktop whose system FreeType
    // differs from the one the JDK bundles it logs "drawing failure for widget ... error occurred
    // in libfreetype" for every widget and the dialog cannot be used, so the ZIP files button was
    // dead there. Swing draws its own chooser and is unaffected.
    @Test
    fun linuxAvoidsTheNativeFileDialog() {
        assertFalse(usesNativeFileDialog("Linux"))
    }

    @Test
    fun macAndWindowsKeepTheirNativeFileDialog() {
        assertTrue(usesNativeFileDialog("Mac OS X"))
        assertTrue(usesNativeFileDialog("Windows 11"))
    }

    @Test
    fun zipChooserSelectsSeveralFilesAndOnlyOffersZips() {
        val chooser = zipFilesChooser("Choose your Snapchat ZIP files", "ZIP archives")

        assertEquals("Choose your Snapchat ZIP files", chooser.dialogTitle)
        assertTrue(chooser.isMultiSelectionEnabled)
        assertEquals(JFileChooser.FILES_ONLY, chooser.fileSelectionMode)
        assertFalse(chooser.isAcceptAllFileFilterUsed)
        assertTrue(chooser.fileFilter.accept(File("memories-1.zip")))
        assertTrue(chooser.fileFilter.accept(File("MEMORIES-2.ZIP")))
        assertFalse(chooser.fileFilter.accept(File("memories_history.json")))
    }

    @Test
    fun historyChooserOffersJsonAndHtmlButNotArchives() {
        val chooser = historyFileChooser("Choose memories_history.json or .html", "Snapchat history")

        assertFalse(chooser.isMultiSelectionEnabled)
        assertTrue(chooser.fileFilter.accept(File("memories_history.json")))
        assertTrue(chooser.fileFilter.accept(File("memories_history.HTML")))
        assertFalse(chooser.fileFilter.accept(File("memories-1.zip")))
    }
}
