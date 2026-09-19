package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.OutputFolderMemory
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.UnenforcedOutputDirectoryLocker
import com.najdev.snapvault.ZipSourceMode
import com.najdev.snapvault.downloader.ArchiveSpace
import com.najdev.snapvault.downloader.DesktopZipPipelineRunner
import com.najdev.snapvault.downloader.ExtractionBudget
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.DesktopMediaProcessor
import com.najdev.snapvault.parser.HtmlMemoryEntry
import com.najdev.snapvault.ui.components.LowSpaceOfferDialog
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import com.najdev.snapvault.viewmodel.DashboardViewModel
import okio.FileSystem
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D20, as the user meets it: an import refused for space offers to delete each ZIP as its
 * contents are imported, and deletes nothing until they say so.
 */
@OptIn(ExperimentalTestApi::class)
class LowSpaceOfferTest {

    private lateinit var workDir: File
    private lateinit var outDir: File

    @BeforeTest
    fun setUp() {
        workDir = File.createTempFile("low-space-offer", "").apply { delete(); mkdirs() }
        outDir = File(workDir, "library").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        workDir.deleteRecursively()
    }

    // The decision is irreversible, so everything it rests on has to be on screen: the numbers,
    // the actual file names, and that the files do not go to the Trash.
    @Test
    fun theOfferNamesTheFilesAndSaysTheDeletionCannotBeUndone() = runComposeUiTest {
        var confirmed = 0
        var dismissed = 0
        setContent {
            SnapVaultTheme(darkMode = true) {
                LowSpaceOfferDialog(
                    archiveNames = listOf("mydata~part1.zip", "mydata~part2.zip"),
                    requiredText = "31.4 GB",
                    availableText = "8.2 GB",
                    reclaimableText = "29.0 GB",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                )
            }
        }

        onNode(hasText("31.4 GB", substring = true)).assertIsDisplayed()
        onNode(hasText("8.2 GB", substring = true)).assertIsDisplayed()
        onNode(hasText("29.0 GB", substring = true)).assertIsDisplayed()
        onNode(hasText("mydata~part1.zip", substring = true)).assertIsDisplayed()
        onNode(hasText("mydata~part2.zip", substring = true)).assertIsDisplayed()
        onNode(hasText("2 ZIP files", substring = true)).assertIsDisplayed()
        onNode(hasText("permanent", substring = true)).assertIsDisplayed()
        onNode(hasText("Trash", substring = true)).assertIsDisplayed()

        onNodeWithText("Not now").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed, "declining must not start anything")

        onNodeWithText("Import and delete each ZIP").performClick()
        assertEquals(1, confirmed)
    }

    private fun exportZip(name: String, memory: String): File {
        val zip = File(workDir, name)
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("memories/$memory"))
            zos.write(ByteArray(2048) { 3 })
            zos.closeEntry()
        }
        return zip
    }

    private class Pickers(private val zips: List<String>) : PlatformPickers {
        override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(null)
        override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(null)
        override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
        override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(zips)
    }

    // End to end through the screen: a run refused for space, the dialog it raises, and the
    // import that follows the user accepting it.
    @Test
    fun acceptingTheOfferImportsAndDeletesTheArchive() = runComposeUiTest {
        val zip = exportZip("part1.zip", "2024-01-01_aaa-main.jpg")
        val gb = 1024L * 1024 * 1024
        val real = DesktopZipPipelineRunner(DesktopMediaProcessor())
        val runner = object : ZipPipelineRunner by real {
            override fun extractionBudget(itemsByZip: Map<String, List<HtmlMemoryEntry>>, outputDir: String) =
                ExtractionBudget(
                    requiredBytes = 9 * gb,
                    availableBytes = 3 * gb,
                    archives = real.extractionBudget(itemsByZip, outputDir)!!.archives.map {
                        ArchiveSpace(it.path, requiredBytes = gb, archiveBytes = gb, onOutputVolume = true)
                    },
                )
        }
        val viewModel = DashboardViewModel(
            zipPipelineRunner = runner,
            mediaProcessor = DesktopMediaProcessor(),
            fileSystem = FileSystem.SYSTEM,
            pickers = Pickers(listOf(zip.path)),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
            outputFolderMemory = object : OutputFolderMemory {
                override fun load() = outDir.path
                override fun save(path: String?) = Unit
            },
            // The offer ships disabled by default (LOW_SPACE_DELETE_ENABLED); this test verifies
            // the mechanism itself still works, for whenever it's re-enabled.
            lowSpaceDeleteEnabled = true,
        ).apply {
            changeZipSourceMode(ZipSourceMode.MultipleFiles)
            pickMultipleZips()
            // Metadata and combining need real tools; extraction is what is under test.
            pipelineOptions.runMetadata = false
            pipelineOptions.runCombine = false
            pipelineOptions.runDedupe = false
        }

        setContent {
            SnapVaultTheme(darkMode = true) { DashboardScreen(viewModel = viewModel, onNavigateToSettings = {}) }
        }

        viewModel.startSync()
        waitUntil(timeoutMillis = 20_000) { !viewModel.isRunning }
        waitForIdle()

        onNodeWithText("Not enough space for this import").assertIsDisplayed()
        // The Dashboard behind the dialog also lists the chosen file, so this only asserts the
        // name is on screen, not that it appears once.
        assertTrue(onAllNodes(hasText("part1.zip", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(zip.exists(), "the offer alone must delete nothing")

        onNodeWithText("Import and delete each ZIP").performClick()
        waitUntil(timeoutMillis = 20_000) { !viewModel.isRunning && viewModel.logs.any { "Low-space import" in it } }
        waitForIdle()

        assertFalse(zip.exists(), "the accepted import deletes each archive it finishes")
        assertTrue(File(outDir, "2024-01-01_aaa-main.jpg").isFile, "and keeps the memories")
        viewModel.dispose()
    }

    // The offer has three known safety gaps (same-sized-different-content verification,
    // unrecognized media, an unguarded history-backup failure) not yet fixed — disabled by
    // default for release (LOW_SPACE_DELETE_ENABLED), even in a scenario that would otherwise
    // qualify for it.
    @Test
    fun theOfferIsDisabledByDefault() = runComposeUiTest {
        val zip = exportZip("part1.zip", "2024-01-01_aaa-main.jpg")
        val gb = 1024L * 1024 * 1024
        val real = DesktopZipPipelineRunner(DesktopMediaProcessor())
        val runner = object : ZipPipelineRunner by real {
            override fun extractionBudget(itemsByZip: Map<String, List<HtmlMemoryEntry>>, outputDir: String) =
                ExtractionBudget(
                    requiredBytes = 9 * gb,
                    availableBytes = 3 * gb,
                    archives = real.extractionBudget(itemsByZip, outputDir)!!.archives.map {
                        ArchiveSpace(it.path, requiredBytes = gb, archiveBytes = gb, onOutputVolume = true)
                    },
                )
        }
        val viewModel = DashboardViewModel(
            zipPipelineRunner = runner,
            mediaProcessor = DesktopMediaProcessor(),
            fileSystem = FileSystem.SYSTEM,
            pickers = Pickers(listOf(zip.path)),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
            outputFolderMemory = object : OutputFolderMemory {
                override fun load() = outDir.path
                override fun save(path: String?) = Unit
            },
            // Default omitted deliberately: this proves LOW_SPACE_DELETE_ENABLED's consequence.
        ).apply {
            changeZipSourceMode(ZipSourceMode.MultipleFiles)
            pickMultipleZips()
        }

        setContent {
            SnapVaultTheme(darkMode = true) { DashboardScreen(viewModel = viewModel, onNavigateToSettings = {}) }
        }

        viewModel.startSync()
        waitUntil(timeoutMillis = 20_000) { !viewModel.isRunning }
        waitForIdle()

        assertNull(viewModel.lowSpaceOffer, "the offer must not be made while the feature is disabled")
        onAllNodes(hasText("Import and delete each ZIP")).fetchSemanticsNodes().let {
            assertTrue(it.isEmpty(), "no dialog offering deletion may appear")
        }
        assertTrue(zip.exists(), "nothing may be deleted when the offer never happened")
        viewModel.dispose()
    }

    @Test
    fun decliningTheOfferLeavesTheArchivesAlone() = runComposeUiTest {
        val zip = exportZip("part1.zip", "2024-01-01_aaa-main.jpg")
        val gb = 1024L * 1024 * 1024
        val real = DesktopZipPipelineRunner(DesktopMediaProcessor())
        val runner = object : ZipPipelineRunner by real {
            override fun extractionBudget(itemsByZip: Map<String, List<HtmlMemoryEntry>>, outputDir: String) =
                ExtractionBudget(
                    requiredBytes = 9 * gb,
                    availableBytes = 3 * gb,
                    archives = real.extractionBudget(itemsByZip, outputDir)!!.archives.map {
                        ArchiveSpace(it.path, requiredBytes = gb, archiveBytes = gb, onOutputVolume = true)
                    },
                )
        }
        val viewModel = DashboardViewModel(
            zipPipelineRunner = runner,
            mediaProcessor = DesktopMediaProcessor(),
            fileSystem = FileSystem.SYSTEM,
            pickers = Pickers(listOf(zip.path)),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
            outputFolderMemory = object : OutputFolderMemory {
                override fun load() = outDir.path
                override fun save(path: String?) = Unit
            },
            // The offer ships disabled by default (LOW_SPACE_DELETE_ENABLED); this test verifies
            // the mechanism itself still works, for whenever it's re-enabled.
            lowSpaceDeleteEnabled = true,
        ).apply {
            changeZipSourceMode(ZipSourceMode.MultipleFiles)
            pickMultipleZips()
        }

        setContent {
            SnapVaultTheme(darkMode = true) { DashboardScreen(viewModel = viewModel, onNavigateToSettings = {}) }
        }

        viewModel.startSync()
        waitUntil(timeoutMillis = 20_000) { !viewModel.isRunning }
        waitForIdle()
        onNodeWithText("Not now").performClick()
        waitForIdle()

        assertTrue(zip.exists())
        onAllNodes(hasText("Not enough space for this import")).fetchSemanticsNodes().let {
            assertTrue(it.isEmpty(), "the dialog should be gone once declined")
        }
        viewModel.dispose()
    }
}
