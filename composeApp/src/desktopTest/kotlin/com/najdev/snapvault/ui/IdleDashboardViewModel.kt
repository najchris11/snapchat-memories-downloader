package com.najdev.snapvault.ui

import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.UnenforcedOutputDirectoryLocker
import com.najdev.snapvault.downloader.CombineResult
import com.najdev.snapvault.downloader.ExtractResult
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.parser.HtmlMemoryEntry
import com.najdev.snapvault.viewmodel.DashboardViewModel
import okio.fakefilesystem.FakeFileSystem

/**
 * A view model for UI tests that only render the Dashboard: nothing picked, no tools, a
 * pipeline runner that does nothing, and a filesystem that is not the real one. Tests that
 * drive a run build their own, because what the runner does is the thing under test there.
 */
internal fun idleDashboardViewModel() = DashboardViewModel(
    zipPipelineRunner = IdleRunner,
    mediaProcessor = IdleTools,
    fileSystem = FakeFileSystem(),
    pickers = NoPickers,
    outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
)

private object NoPickers : PlatformPickers {
    override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(null)
    override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(null)
    override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
    override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
}

private object IdleTools : MediaProcessor {
    override fun checkExifTool() = true
    override fun checkFFmpeg() = true
    override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = true
    override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = true
    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = true
}

private object IdleRunner : ZipPipelineRunner {
    override fun listZipFiles(folderPath: String): List<String> = emptyList()
    override suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit,
    ) = Unit
    override suspend fun extractDownloadedArchives(
        outputDir: String,
        archivePaths: List<String>,
        onWarn: (String) -> Unit,
    ): List<String> = emptyList()
    override suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit,
        onMetaStart: (total: Int) -> Unit,
        onMetaError: ((String) -> Unit)?,
        onProgress: (CombineResult) -> Unit,
    ) = Unit
}
