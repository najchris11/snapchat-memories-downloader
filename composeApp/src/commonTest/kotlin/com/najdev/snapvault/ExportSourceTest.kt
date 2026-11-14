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
