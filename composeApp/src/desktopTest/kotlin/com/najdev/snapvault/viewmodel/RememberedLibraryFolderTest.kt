package com.najdev.snapvault.viewmodel

import com.najdev.snapvault.OutputFolderMemory
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.UnenforcedOutputDirectoryLocker
import com.najdev.snapvault.downloader.NoOpZipPipelineRunner
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reopening the app left the library folder unset, so every launch began by finding the folder
 * again in a native picker — while the favorites and media in it were still there. Reported
 * during the audit's restart walkthrough; no finding number.
 *
 * The saved folder is only restored if it is still a folder: an external drive that is not
 * plugged in, or a folder since deleted, must not come back as a selection the Library then
 * scans and reports as empty.
 */
class RememberedLibraryFolderTest {

    private class Pickers(private val folder: String?) : PlatformPickers {
        override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(null)
        override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(folder)
        override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
        override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
    }

    private fun viewModel(
        fileSystem: FakeFileSystem,
        saved: String?,
        pick: String? = null,
        onSave: (String?) -> Unit = {},
    ) = DashboardViewModel(
        zipPipelineRunner = NoOpZipPipelineRunner,
        mediaProcessor = FakeMediaProcessor(),
        fileSystem = fileSystem,
        pickers = Pickers(pick),
        outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        outputFolderMemory = object : OutputFolderMemory {
            override fun load() = saved
            override fun save(path: String?) = onSave(path)
        },
    )

    @Test
    fun theFolderFromTheLastSessionComesBack() {
        val fs = FakeFileSystem().apply { createDirectories("/library".toPath()) }

        val viewModel = viewModel(fs, saved = "/library")

        assertEquals("/library", viewModel.downloadFolder)
        viewModel.dispose()
    }

    @Test
    fun aFolderThatIsGoneIsNotRestored() {
        val fs = FakeFileSystem()

        val viewModel = viewModel(fs, saved = "/library/on/a/drive/that/is/unplugged")

        assertNull(viewModel.downloadFolder, "an unreachable folder must not look selected")
        viewModel.dispose()
    }

    // A file at that path is not a library either — restoring it would have the Library scan
    // something that cannot hold memories.
    @Test
    fun aPathThatIsNowAFileIsNotRestored() {
        val fs = FakeFileSystem().apply {
            createDirectories("/parent".toPath())
            write("/parent/library".toPath()) { writeUtf8("not a folder") }
        }

        val viewModel = viewModel(fs, saved = "/parent/library")

        assertNull(viewModel.downloadFolder)
        viewModel.dispose()
    }

    @Test
    fun choosingAFolderRecordsItForNextTime() {
        val fs = FakeFileSystem().apply { createDirectories("/chosen".toPath()) }
        val saves = mutableListOf<String?>()

        val viewModel = viewModel(fs, saved = null, pick = "/chosen", onSave = { saves += it })
        viewModel.pickOutputFolder()

        assertTrue(saves.contains("/chosen"), "picked folder was not remembered: $saves")
        viewModel.dispose()
    }

    @Test
    fun nothingIsRestoredWhenNothingWasSaved() {
        val viewModel = viewModel(FakeFileSystem(), saved = null)

        assertNull(viewModel.downloadFolder)
        viewModel.dispose()
    }
}
