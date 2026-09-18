package com.najdev.snapvault.viewmodel

import com.najdev.snapvault.ImportManifest
import com.najdev.snapvault.OutputFolderMemory
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.ZipSourceMode
import com.najdev.snapvault.UnenforcedOutputDirectoryLocker
import com.najdev.snapvault.downloader.ArchiveSpace
import com.najdev.snapvault.downloader.DesktopZipPipelineRunner
import com.najdev.snapvault.downloader.ExtractionBudget
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.DesktopMediaProcessor
import com.najdev.snapvault.parser.HtmlMemoryEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D20: importing one archive at a time and deleting each as its contents land, for a user who
 * does not have room for every archive and all their contents at once.
 *
 * These run the real extractor over real ZIP files in a temporary directory: deleting the
 * user's only copy of their memories is what this mode does when it goes wrong, so the parts
 * that decide to delete are not faked.
 */
class LowSpaceImportTest {

    private lateinit var workDir: File
    private lateinit var zipDir: File
    private lateinit var outDir: File

    @BeforeTest
    fun setUp() {
        workDir = File.createTempFile("low-space-import", "").apply { delete(); mkdirs() }
        zipDir = File(workDir, "zips").apply { mkdirs() }
        outDir = File(workDir, "library").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun exportZip(name: String, memories: List<String>, historyJson: String? = null): File {
        val zip = File(zipDir, name)
        ZipOutputStream(zip.outputStream()).use { zos ->
            memories.forEach { fileName ->
                zos.putNextEntry(ZipEntry("memories/$fileName"))
                zos.write(ByteArray(2048) { fileName.length.toByte() })
                zos.closeEntry()
            }
            if (historyJson != null) {
                zos.putNextEntry(ZipEntry("json/memories_history.json"))
                zos.write(historyJson.toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    private class Pickers(private val zips: List<String>) : PlatformPickers {
        override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(null)
        override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(null)
        override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
        override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(zips)
    }

    private fun viewModel(zips: List<File>, runner: ZipPipelineRunner? = null): DashboardViewModel {
        val real = DesktopZipPipelineRunner(DesktopMediaProcessor())
        return DashboardViewModel(
            zipPipelineRunner = runner ?: real,
            mediaProcessor = DesktopMediaProcessor(),
            fileSystem = FileSystem.SYSTEM,
            pickers = Pickers(zips.map { it.path }),
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
            outputFolderMemory = object : OutputFolderMemory {
                override fun load() = outDir.path
                override fun save(path: String?) = Unit
            },
        ).apply {
            changeZipSourceMode(ZipSourceMode.MultipleFiles)
            pickMultipleZips()
        }
    }

    // Extraction only: metadata and combining need real tools and are not what this is about.
    private fun DashboardViewModel.startImport(lowSpaceMode: Boolean) = startSync(
        runDownload = false,
        runMetadata = false,
        experimentalMetadataMatching = false,
        runCombine = false,
        runDedupe = false,
        dryRun = true,
        lowSpaceMode = lowSpaceMode,
    )

    private fun await(viewModel: DashboardViewModel) = runBlocking {
        withTimeout(20_000) { while (viewModel.isRunning) delay(10) }
    }

    @Test
    fun eachArchiveIsDeletedOnceItsContentsAreOnDisk() {
        val first = exportZip("part1.zip", listOf("2024-01-01_aaa-main.jpg"))
        val second = exportZip("part2.zip", listOf("2024-01-02_bbb-main.jpg", "2024-01-02_bbb-overlay.png"))
        val viewModel = viewModel(listOf(first, second))

        viewModel.startImport(lowSpaceMode = true)
        await(viewModel)

        assertFalse(first.exists(), "part1.zip was imported and should be gone")
        assertFalse(second.exists(), "part2.zip was imported and should be gone")
        listOf("2024-01-01_aaa-main.jpg", "2024-01-02_bbb-main.jpg", "2024-01-02_bbb-overlay.png").forEach {
            assertTrue(File(outDir, it).isFile, "$it should have been extracted")
        }
        val manifest = ImportManifest.read(FileSystem.SYSTEM, outDir.path).associateBy { it.name }
        assertTrue(manifest.getValue("part1.zip").deleted)
        assertTrue(manifest.getValue("part2.zip").deleted)
        viewModel.dispose()
    }

    // The ordinary import must not delete anything, however tight the disk is.
    @Test
    fun anOrdinaryImportLeavesEveryArchiveWhereItIs() {
        val zip = exportZip("part1.zip", listOf("2024-01-01_aaa-main.jpg"))
        val viewModel = viewModel(listOf(zip))

        viewModel.startImport(lowSpaceMode = false)
        await(viewModel)

        assertTrue(zip.exists(), "an ordinary import deletes no archives")
        assertEquals(emptyList(), ImportManifest.read(FileSystem.SYSTEM, outDir.path).filter { it.deleted })
        viewModel.dispose()
    }

    // A file of the same name already in the library is skipped by extraction, so this
    // archive's own bytes are not the ones on disk. Deleting it would destroy the only copy.
    @Test
    fun anArchiveWhoseContentsDidNotLandIsKept() {
        val zip = exportZip("part1.zip", listOf("2024-01-01_aaa-main.jpg"))
        File(outDir, "2024-01-01_aaa-main.jpg").writeBytes(ByteArray(11) { 9 })
        val viewModel = viewModel(listOf(zip))

        viewModel.startImport(lowSpaceMode = true)
        await(viewModel)

        assertTrue(zip.exists(), "an unverified archive must survive")
        val record = ImportManifest.read(FileSystem.SYSTEM, outDir.path).single { it.name == "part1.zip" }
        assertFalse(record.deleted)
        assertNotNull(record.keptBecause)
        assertTrue(viewModel.logs.any { "part1.zip" in it && "kept" in it.lowercase() }, viewModel.logs.toString())
        viewModel.dispose()
    }

    // GPS matching reads memories_history.json out of the archives, at a step that runs after
    // extraction. Deleting the archives without keeping it would quietly cost every later run
    // its location data.
    @Test
    fun theExportsHistoryIsKeptBeforeItsArchiveIsDeleted() {
        val history = """{"Saved Media":[{"Date":"2024-01-01 00:00:00 UTC","Media Type":"Image"}]}"""
        val zip = exportZip("part1.zip", listOf("2024-01-01_aaa-main.jpg"), historyJson = history)
        val viewModel = viewModel(listOf(zip))

        viewModel.startImport(lowSpaceMode = true)
        await(viewModel)

        assertFalse(zip.exists())
        val stashed = File(outDir, "${ImportManifest.DIR_NAME}/history/part1.zip.json")
        assertEquals(history, stashed.readText(), "the export's history must outlive its archive")
        viewModel.dispose()
    }

    private fun budgetOverriding(real: ZipPipelineRunner, budget: (List<ArchiveSpace>) -> ExtractionBudget) =
        object : ZipPipelineRunner by real {
            override fun extractionBudget(itemsByZip: Map<String, List<HtmlMemoryEntry>>, outputDir: String) =
                budget(real.extractionBudget(itemsByZip, outputDir)!!.archives)
        }

    @Test
    fun animportThatWillNotFitIsOfferedTheModeRatherThanOnlyRefused() {
        val zips = listOf(exportZip("part1.zip", listOf("2024-01-01_aaa-main.jpg")))
        val gb = 1024L * 1024 * 1024
        // Four archives' worth of contents, one archive's worth of room, and the archives are
        // on the output drive: does not fit as it stands, fits one at a time.
        val runner = budgetOverriding(DesktopZipPipelineRunner(DesktopMediaProcessor())) { archives ->
            ExtractionBudget(
                requiredBytes = 8 * gb,
                availableBytes = 3 * gb,
                archives = archives.map { it.copy(requiredBytes = 2 * gb, archiveBytes = 2 * gb, onOutputVolume = true) },
            )
        }
        val viewModel = viewModel(zips, runner)

        viewModel.startImport(lowSpaceMode = false)
        await(viewModel)

        val offer = assertNotNull(viewModel.lowSpaceOffer, "the user was refused with no way forward")
        assertEquals(listOf("part1.zip"), offer.archiveNames)
        assertEquals(2 * gb, offer.reclaimableBytes)
        assertTrue(zips.single().exists(), "an offer deletes nothing on its own")
        viewModel.dispose()
    }

    @Test
    fun anImportThatWillNotFitEvenOneArchiveAtATimeIsNotOfferedTheMode() {
        val zips = listOf(exportZip("part1.zip", listOf("2024-01-01_aaa-main.jpg")))
        val gb = 1024L * 1024 * 1024
        val runner = budgetOverriding(DesktopZipPipelineRunner(DesktopMediaProcessor())) { archives ->
            ExtractionBudget(
                requiredBytes = 40 * gb,
                availableBytes = 3 * gb,
                archives = archives.map { it.copy(requiredBytes = 40 * gb, archiveBytes = 40 * gb, onOutputVolume = true) },
            )
        }
        val viewModel = viewModel(zips, runner)

        viewModel.startImport(lowSpaceMode = false)
        await(viewModel)

        assertNull(viewModel.lowSpaceOffer, "deleting these archives would not make the import fit")
        assertTrue(viewModel.logs.any { "free space" in it.lowercase() }, viewModel.logs.toString())
        viewModel.dispose()
    }

    @Test
    fun archivesOnAnotherDriveAreNotOfferedTheMode() {
        val zips = listOf(exportZip("part1.zip", listOf("2024-01-01_aaa-main.jpg")))
        val gb = 1024L * 1024 * 1024
        val runner = budgetOverriding(DesktopZipPipelineRunner(DesktopMediaProcessor())) { archives ->
            ExtractionBudget(
                requiredBytes = 8 * gb,
                availableBytes = 3 * gb,
                archives = archives.map { it.copy(requiredBytes = 2 * gb, archiveBytes = 2 * gb, onOutputVolume = false) },
            )
        }
        val viewModel = viewModel(zips, runner)

        viewModel.startImport(lowSpaceMode = false)
        await(viewModel)

        assertNull(viewModel.lowSpaceOffer, "deleting archives on another drive frees nothing here")
        viewModel.dispose()
    }
}
