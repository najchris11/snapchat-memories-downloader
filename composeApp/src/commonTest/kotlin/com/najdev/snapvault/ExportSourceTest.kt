package com.najdev.snapvault

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExportSourceTest {
    @Test
    fun detectsZipFilesAndPrefersArchivesInMixedFolders() {
        val fs = FakeFileSystem()
        fs.createDirectories("/export/json".toPath())
        fs.write("/export/data.ZIP".toPath()) { writeUtf8("zip") }
        fs.write("/export/json/memories_history.json".toPath()) { writeUtf8("{}") }
        assertEquals(ExportSource.ZipFile("/export/data.ZIP"), detectExportSource(fs, "/export/data.ZIP"))
        assertEquals(ExportSource.ZipFolder("/export"), detectExportSource(fs, "/export"))
    }
    @Test
    fun recognizesExtractedHistoryAndRejectsEmptyOrUnsupportedPaths() {
        val fs = FakeFileSystem()
        fs.createDirectories("/export/html".toPath())
        assertNull(detectExportSource(fs, "/export"))
        assertNull(detectExportSource(fs, "/missing"))
        fs.write("/export/photo.jpg".toPath()) { writeUtf8("photo") }
        assertNull(detectExportSource(fs, "/export/photo.jpg"))
        fs.write("/export/html/memories_history.html".toPath()) { writeUtf8("html") }
        assertEquals(ExportSource.History("/export/html/memories_history.html"), detectExportSource(fs, "/export"))
        fs.createDirectories("/export/json".toPath())
        fs.write("/export/json/memories_history.json".toPath()) { writeUtf8("{}") }
        assertEquals(ExportSource.History("/export/json/memories_history.json"), detectExportSource(fs, "/export"))
        assertEquals(ExportSource.History("/export/json/memories_history.json"), detectExportSource(fs, "/export/json/memories_history.json"))
    }
}

/**
 * Regression from a real export: iOS showed the source as a full sandbox path
 * ("/Users/…/CoreSimulator/Devices/…/Documents/Imports/mydata~….zip") and the Android log named
 * each ZIP by its encoded document ID ("raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fmydata….zip").
 * People recognise a ZIP by its file name, which is all [sourceDisplayName] keeps.
 */
class SourceDisplayNameTest {
    @Test
    fun aFilesystemPathShowsOnlyItsFileName() {
        assertEquals("mydata~1788374792646-28.zip", sourceDisplayName("/Users/me/Library/Developer/CoreSimulator/Devices/C59D/data/Documents/Imports/mydata~1788374792646-28.zip"))
        assertEquals("export.zip", sourceDisplayName("C:\\Users\\me\\Downloads\\export.zip"))
        assertEquals("export", sourceDisplayName("/home/me/export/"))
    }

    @Test
    fun anEncodedDocumentIdShowsItsDecodedFileName() {
        assertEquals("mydata~1788374792646-28.zip", sourceDisplayName("raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fmydata~1788374792646-28.zip"))
        assertEquals("mydata.zip", sourceDisplayName("content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fmydata.zip"))
        assertEquals("Snap export.zip", sourceDisplayName("content://com.android.externalstorage.documents/document/primary%3ADownload%2FSnap%20export.zip"))
    }

    @Test
    fun malformedEscapesAreLeftAlone() {
        assertEquals("100%done.zip", sourceDisplayName("/tmp/100%done.zip"))
    }
}
