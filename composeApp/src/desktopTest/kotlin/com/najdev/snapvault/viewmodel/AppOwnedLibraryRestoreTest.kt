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

/**
 * Regression: after reinstalling the iOS app, the Library came back empty and the Dashboard
 * asked to "Use on-device library" again. The remembered path held the old app container's
 * ID (".../Application/07D13C6E-.../Documents/SnapVault Library"), which iOS replaces on a
 * reinstall or update, so the restore saw a missing folder and dropped the selection. On a
 * platform with an app-owned library the current library is selected at launch instead.
 */
class AppOwnedLibraryRestoreTest {
    private fun viewModel(fs: FakeFileSystem, appOwned: Boolean) = DashboardViewModel(
        NoOpZipPipelineRunner, FakeMediaProcessor(), fs,
        object : PlatformPickers {
            override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(null)
            override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult("/container-NEW/Documents/SnapVault Library")
            override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
            override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
        },
        outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        outputFolderMemory = object : OutputFolderMemory {
            override fun load() = "/container-OLD/Documents/SnapVault Library"
            override fun save(path: String?) = Unit
        },
        usesAppOwnedLibrary = appOwned,
    )

    @Test
    fun theCurrentAppOwnedLibraryIsSelectedAtLaunchNotAStaleRememberedPath() {
        val fs = FakeFileSystem().apply { createDirectories("/container-NEW/Documents/SnapVault Library".toPath()) }
        val vm = viewModel(fs, appOwned = true)
        assertEquals("/container-NEW/Documents/SnapVault Library", vm.downloadFolder)
        vm.dispose()
    }

    @Test
    fun desktopStillOnlyRestoresARememberedFolderThatExists() {
        val vm = viewModel(FakeFileSystem(), appOwned = false)
        assertNull(vm.downloadFolder, "a missing remembered folder is not restored, and no picker opens at launch")
        vm.dispose()
    }
}
