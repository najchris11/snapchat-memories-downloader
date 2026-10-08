package com.najdev.snapvault.viewmodel

import com.najdev.snapvault.OutputFolderMemory
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.ZipSourceMode
import com.najdev.snapvault.UnenforcedOutputDirectoryLocker
import com.najdev.snapvault.VaultIndex
import com.najdev.snapvault.downloader.DesktopZipPipelineRunner
import com.najdev.snapvault.downloader.ExtractResult
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.parser.HtmlMemoryEntry
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.scanMediaFiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.time.Instant
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives the ZIP-folder route from picker callbacks through real files and the Library scan. */
class ZipFolderEndToEndTest {
    private lateinit var root: File
    private lateinit var zipFolder: File
    private lateinit var library: File

    @BeforeTest
    fun setUp() {
        // test-data is ignored by git. Keep each generated export and resulting library on
        // disk so a CLI-only reviewer can inspect or rerun them across branch switches.
        // A unique directory keeps repeated/full-suite runs independent of old output.
        val repository = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val fixtures = File(repository, "test-data/zip-folder-e2e").apply { mkdirs() }
        root = Files.createTempDirectory(fixtures.toPath(), "run-").toFile()
        zipFolder = File(root, "exports").apply { mkdirs() }
        library = File(root, "library").apply { mkdirs() }
    }

    private class Pickers(private val zips: String, private val output: String, private val chosenZips: List<String> = emptyList()) : PlatformPickers {
        override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(null)
        override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(output)
        override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(zips)
        override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(chosenZips)
    }

    private class RecordingMetadata : MediaProcessor {
        val gps = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val dates = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        // Metadata progress is overwritten by the next phase, so it is read as each file is tagged.
        var probe: () -> Unit = {}

        override fun checkExifTool() = true
        override fun checkFFmpeg() = false
        override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = false
        override fun writeGpsMetadata(
            filePath: String,
            latitude: Double,
            longitude: Double,
            dateStr: String?,
        ): Boolean {
            probe()
            if (!File(filePath).isFile || latitude != 40.012688 || longitude != -83.066986) return false
            gps += File(filePath).name to (dateStr ?: "")
            return true
        }

        override fun writeDateMetadata(filePath: String, dateTimeUtc: String): Boolean {
            probe()
            if (!File(filePath).isFile) return false
            dates += File(filePath).name to dateTimeUtc
            return true
        }
    }

    private val progressSamples = Collections.synchronizedList(mutableListOf<String>())

    // Extraction progress is overwritten by the next phase, so it is read as each file lands.
    private inner class SamplingRunner(
        private val real: ZipPipelineRunner,
        private val record: (String) -> Unit,
    ) : ZipPipelineRunner by real {
        override suspend fun extractAll(
            itemsByZip: Map<String, List<HtmlMemoryEntry>>,
            outputDir: String,
            workerCount: Int,
            onProgress: (ExtractResult) -> Unit,
        ) = real.extractAll(itemsByZip, outputDir, workerCount) { result ->
            onProgress(result)
            record(lateViewModel.progressText)
        }
    }

    private lateinit var lateViewModel: DashboardViewModel

    private fun viewModel(metadata: RecordingMetadata, vararg chosenZips: String): DashboardViewModel = DashboardViewModel(
        zipPipelineRunner = SamplingRunner(DesktopZipPipelineRunner(metadata)) { progressSamples += it },
        mediaProcessor = metadata,
        fileSystem = FileSystem.SYSTEM,
        pickers = Pickers(zipFolder.path, library.path, chosenZips.toList()),
        outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        outputFolderMemory = OutputFolderMemory.None,
    ).apply {
        lateViewModel = this
        metadata.probe = { progressSamples += progressText }
        pickZipFolder()
        pickOutputFolder()
    }

    private fun awaitCompletion(viewModel: DashboardViewModel) = runBlocking {
        withTimeout(30_000) {
            while (viewModel.isRunning) delay(10)
        }
    }

    private fun DashboardViewModel.importFolder() {
        startSync(
            runDownload = false,
            runMetadata = true,
            experimentalMetadataMatching = true,
            runCombine = false,
            runDedupe = false,
            dryRun = true,
        )
        awaitCompletion(this)
    }

    private fun jpeg(rgb: Int): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        for (x in 0..1) for (y in 0..1) image.setRGB(x, y, rgb)
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun exportZip(
        name: String,
        mediaName: String,
        bytes: ByteArray,
        capturedAt: String,
        history: String? = null,
        overlay: Pair<String, ByteArray>? = null,
    ): File {
        val archive = File(zipFolder, name)
        val epoch = Instant.parse(capturedAt).epochSecond.toInt()
        val timestampExtra = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x5455.toShort()).putShort(5).put(1).putInt(epoch).array()
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("memories/$mediaName").apply {
                time = epoch.toLong() * 1_000
                extra = timestampExtra
            })
            zip.write(bytes)
            zip.closeEntry()
            if (overlay != null) {
                zip.putNextEntry(ZipEntry("memories/${overlay.first}"))
                zip.write(overlay.second)
                zip.closeEntry()
            }
            if (history != null) {
                zip.putNextEntry(ZipEntry("json/memories_history.json"))
                zip.write(history.toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }

    // A release smoke test must follow the user's folder choice, not hand the ViewModel a
    // preselected ZIP list. That omission would miss a broken listZipFiles implementation.
    @Test
    fun chosenZipFolderImportsRealArchivesAndRerunsWithoutReplacingMedia() {
        val firstName = "2024-03-11_AAA-main.jpg"
        val secondName = "2024-03-12_BBB-main.jpg"
        val firstBytes = jpeg(0xCC3322)
        val secondBytes = jpeg(0x2266CC)
        val history = """{"Saved Media":[{"Date":"2024-03-11 12:34:56 UTC","Media Type":"Image","Location":"Latitude, Longitude: 40.012688, -83.066986"}]}"""
        val firstZip = exportZip("memories-2.zip", firstName, firstBytes, "2024-03-11T12:34:56Z", history)
        val secondZip = exportZip("memories-10.zip", secondName, secondBytes, "2024-03-12T08:09:10Z")
        File(zipFolder, "notes.txt").writeText("not an archive")
        val metadata = RecordingMetadata()
        val viewModel = viewModel(metadata)

        try {
            viewModel.importFolder()

            assertEquals("Run complete", viewModel.progressText, viewModel.logs.joinToString("\n"))
            // The first run extracts both files; the count template is "Extracting: done / total".
            assertTrue("Extracting: 2 / 2" in progressSamples, progressSamples.toString())
            assertTrue(progressSamples.any { it.startsWith("Metadata: 0 / ") }, progressSamples.toString())
            assertFalse(viewModel.hasWarnings, viewModel.logs.joinToString("\n"))
            assertTrue(viewModel.logs.any { "Found 2 ZIP files." in it })
            val firstLog = viewModel.logs.indexOfFirst { "memories-2.zip:" in it }
            val secondLog = viewModel.logs.indexOfFirst { "memories-10.zip:" in it }
            assertTrue(firstLog >= 0 && secondLog > firstLog, "numbered archives must be read in numeric order")
            assertContentEquals(firstBytes, File(library, firstName).readBytes())
            assertContentEquals(secondBytes, File(library, secondName).readBytes())
            assertEquals(listOf(firstName to "2024-03-11 12:34:56 UTC"), metadata.gps.toList())
            assertEquals(listOf(secondName to "2024-03-12 08:09:10 UTC"), metadata.dates.toList())

            val index = VaultIndex.read(FileSystem.SYSTEM, library.path)
            assertTrue(index.getValue(firstName).hasGps)
            assertFalse(index.getValue(secondName).hasGps)
            assertEquals(setOf(firstName, secondName), scanMediaFiles(library.path).map { File(it.id).name }.toSet())
            assertTrue(firstZip.isFile && secondZip.isFile, "ordinary imports must keep source archives")

            viewModel.importFolder()
            assertEquals("Run complete", viewModel.progressText, viewModel.logs.joinToString("\n"))
            assertTrue(viewModel.logs.any { "Extracted 0 new, 2 already existed" in it })
            assertContentEquals(firstBytes, File(library, firstName).readBytes())
            assertContentEquals(secondBytes, File(library, secondName).readBytes())
        } finally {
            viewModel.dispose()
        }
    }

    // A file picker used to leave the folder mode active, so Start scanned a folder
    // instead of importing the selected archive. Re-running must also skip existing media.
    @Test
    fun chosenZipImportsAndRerunsWithoutReplacingMedia() {
        val mediaName = "2024-03-11_AAA-main.jpg"
        val overlayName = "2024-03-11_AAA-overlay.png"
        val bytes = jpeg(0xCC3322)
        val overlayBytes = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        val archive = exportZip(
            "memories-1.zip", mediaName, bytes, "2024-03-11T12:34:56Z",
            overlay = overlayName to overlayBytes,
        )
        val viewModel = viewModel(RecordingMetadata(), archive.path)

        try {
            viewModel.pickMultipleZips()
            assertEquals(listOf(archive.path), viewModel.selectedZipFiles)
            assertEquals(null, viewModel.zipFolder)
            viewModel.importFolder()

            assertEquals("Run complete", viewModel.progressText, viewModel.logs.joinToString("\n"))
            assertTrue("Extracting: 2 / 2" in progressSamples, progressSamples.toString())
            assertContentEquals(bytes, File(library, mediaName).readBytes())
            assertContentEquals(overlayBytes, File(library, overlayName).readBytes())
            assertTrue(VaultIndex.read(FileSystem.SYSTEM, library.path).containsKey(mediaName))
            assertEquals(setOf(mediaName, overlayName), scanMediaFiles(library.path).map { File(it.id).name }.toSet())

            viewModel.importFolder()
            assertTrue(viewModel.logs.any { "Extracted 0 new, 2 already existed" in it })
            assertContentEquals(bytes, File(library, mediaName).readBytes())
            assertContentEquals(overlayBytes, File(library, overlayName).readBytes())
        } finally {
            viewModel.dispose()
        }
    }

    // An archive without Snapchat memories must fail before creating an empty library index.
    @Test
    fun chosenZipWithoutMemoriesFailsWithoutWritingMedia() {
        val archive = exportZip("unrelated.zip", "not-a-snapchat-file.jpg", jpeg(0x449944), "2024-03-11T12:34:56Z")
        exportZip("memories-2.zip", "2024-03-12_BBB-main.jpg", jpeg(0x2266CC), "2024-03-12T08:09:10Z")
        val viewModel = viewModel(RecordingMetadata(), archive.path)

        try {
            viewModel.pickMultipleZips()
            viewModel.importFolder()
            assertEquals("Failed", viewModel.progressText, viewModel.logs.joinToString("\n"))
            assertTrue(viewModel.logs.any { "Found no Snapchat memories" in it })
            assertTrue(scanMediaFiles(library.path).isEmpty())
            assertTrue(VaultIndex.read(FileSystem.SYSTEM, library.path).isEmpty())
        } finally {
            viewModel.dispose()
        }
    }

    // The ZIP picker takes several archives at once, and they need not share a folder. This
    // is the route #65 flagged as lost when the Folder/Files toggle went away: a folder scan
    // cannot reach archives saved in two places, so the selection itself has to.
    @Test
    fun zipsChosenFromDifferentFoldersAreImportedInOneRun() {
        val firstName = "2024-03-11_AAA-main.jpg"
        val secondName = "2024-03-12_BBB-main.jpg"
        val first = exportZip("memories-1.zip", firstName, jpeg(0xCC3322), "2024-03-11T12:34:56Z")
        val moved = exportZip("memories-2.zip", secondName, jpeg(0x2266CC), "2024-03-12T08:09:10Z")
        val elsewhere = File(root, "downloads").apply { mkdirs() }
        val second = File(elsewhere, moved.name).also { moved.renameTo(it) }
        val viewModel = viewModel(RecordingMetadata(), first.path, second.path)

        try {
            viewModel.pickMultipleZips()
            assertEquals(listOf(first.path, second.path), viewModel.selectedZipFiles)
            assertEquals(null, viewModel.zipFolder)
            viewModel.importFolder()

            assertEquals("Run complete", viewModel.progressText, viewModel.logs.joinToString("\n"))
            assertEquals(setOf(firstName, secondName), scanMediaFiles(library.path).map { File(it.id).name }.toSet())
        } finally {
            viewModel.dispose()
        }
    }

    @Test
    fun choosingFolderAfterZipsClearsTheFileSelection() {
        val archive = exportZip("memories-1.zip", "2024-03-11_AAA-main.jpg", jpeg(0xCC3322), "2024-03-11T12:34:56Z")
        val viewModel = viewModel(RecordingMetadata(), archive.path)
        try {
            viewModel.pickMultipleZips()
            viewModel.pickZipFolder()
            assertEquals(zipFolder.path, viewModel.zipFolder)
            assertTrue(viewModel.selectedZipFiles.isEmpty())
        } finally {
            viewModel.dispose()
        }
    }

    @Test
    fun cancellingTheZipPickerKeepsTheChosenFolder() {
        val viewModel = viewModel(RecordingMetadata())
        try {
            viewModel.pickMultipleZips()
            assertEquals(zipFolder.path, viewModel.zipFolder)
            assertTrue(viewModel.selectedZipFiles.isEmpty())
        } finally {
            viewModel.dispose()
        }
    }

    @Test
    fun emptyZipFolderReportsNoZipFiles() {
        val viewModel = viewModel(RecordingMetadata())
        try {
            viewModel.importFolder()
            assertEquals("Failed", viewModel.progressText, viewModel.logs.joinToString("\n"))
            assertTrue(viewModel.logs.any { "No ZIP files found in the selected folder." in it })
            assertTrue(VaultIndex.read(FileSystem.SYSTEM, library.path).isEmpty())
        } finally {
            viewModel.dispose()
        }
    }

    @Test
    fun startingWithoutAZipSourceExplainsWhatToChoose() {
        val viewModel = viewModel(RecordingMetadata())
        try {
            viewModel.changeZipSourceMode(ZipSourceMode.Folder)
            viewModel.importFolder()
            assertEquals("Failed", viewModel.progressText, viewModel.logs.joinToString("\n"))
            assertTrue(viewModel.logs.any { "Choose a ZIP folder or file before starting." in it })
            assertTrue(VaultIndex.read(FileSystem.SYSTEM, library.path).isEmpty())
        } finally {
            viewModel.dispose()
        }
    }

    // A selected archive is not proof it contains memories. The old empty-import path could
    // finish successfully and leave an apparently valid empty library/index behind.
    @Test
    fun zipFolderWithNoSnapchatMemoriesFailsWithoutWritingMedia() {
        exportZip("unrelated.zip", "not-a-snapchat-file.jpg", jpeg(0x449944), "2024-03-11T12:34:56Z")
        val viewModel = viewModel(RecordingMetadata())

        try {
            viewModel.importFolder()

            assertEquals("Failed", viewModel.progressText, viewModel.logs.joinToString("\n"))
            assertTrue(viewModel.logs.any { "Found no Snapchat memories" in it })
            assertTrue(scanMediaFiles(library.path).isEmpty())
            assertTrue(VaultIndex.read(FileSystem.SYSTEM, library.path).isEmpty())
        } finally {
            viewModel.dispose()
        }
    }
}
