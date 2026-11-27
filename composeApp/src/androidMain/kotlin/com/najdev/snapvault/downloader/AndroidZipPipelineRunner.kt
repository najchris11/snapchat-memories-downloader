package com.najdev.snapvault.downloader

import android.system.ErrnoException
import com.najdev.snapvault.ContextHolder
import com.najdev.snapvault.AndroidDocumentAccess
import com.najdev.snapvault.JvmDocumentSources
import java.io.IOException
import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.metadata.exportAndroidVideoOverlay
import com.najdev.snapvault.parser.HtmlMemoryEntry
import java.io.File

/**
 * The ZIP pipeline on Android.
 *
 * Extraction is shared with desktop through [ZipExtractEngine] (`jvmSharedMain`), archive
 * discovery uses [sortExportZipPaths] (`commonMain`), and image overlay compositing delegates
 * to [StagedImageOverlayCombiner] (`jvmSharedMain`) with kernel-atomic non-overwriting publish
 * via [Os.link] (available since API 21, safe on `minSdk = 24`) and filename-date metadata
 * fallback callbacks (#51, #64).
 */
class AndroidZipPipelineRunner(
    private val mediaProcessor: MediaProcessor,
) : ZipPipelineRunner {

    private val extractor = ZipExtractEngine()
    private val streaming = StreamingZipExtractor { path -> JvmDocumentSources.open?.invoke(path) ?: throw IOException("Document access is unavailable") }
    private val combiner = StagedImageOverlayCombiner(
        mediaProcessor = mediaProcessor,
        publishNoOverwrite = ::androidAtomicNoOverwritePublish,
        hasCaptureTimestamp = ::hasExifCaptureTimestamp,
        videoEncoder = { main, overlay, staged ->
            val context = ContextHolder.context ?: return@StagedImageOverlayCombiner false
            exportAndroidVideoOverlay(context, main, overlay, staged)
        },
    )

    override fun listZipFiles(folderPath: String): List<String> =
        if (folderPath.startsWith("content://")) {
            ContextHolder.context?.let { AndroidDocumentAccess(it).archives(folderPath) }.orEmpty()
        } else sortExportZipPaths(
            File(folderPath)
                .listFiles { f -> f.isFile }
                ?.map { it.absolutePath }
                .orEmpty(),
        )

    override fun extractionBudget(itemsByZip: Map<String, List<HtmlMemoryEntry>>, outputDir: String) =
        if (itemsByZip.keys.any { it.startsWith("content://") }) null else extractor.extractionBudget(itemsByZip, outputDir)

    override fun availableSpace(outputDir: String) = extractor.availableSpace(outputDir)

    override fun verifyExtraction(zipPath: String, entries: List<HtmlMemoryEntry>, outputDir: String) =
        extractor.verifyExtraction(zipPath, entries, outputDir)

    override suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit,
    ) {
        val (documents, local) = itemsByZip.entries.partition { it.key.startsWith("content://") }
        if (local.isNotEmpty()) extractor.extractAll(local.associate { it.key to it.value }, outputDir, workerCount, onProgress)
        documents.forEach { streaming.extract(it.key, it.value, outputDir, onProgress) }
    }

    override suspend fun extractDownloadedArchives(
        outputDir: String,
        archivePaths: List<String>,
        onWarn: (String) -> Unit,
    ): List<String> = extractor.extractDownloadedArchives(outputDir, archivePaths, onWarn)

    override suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onStart: (total: Int) -> Unit,
        onMetaStart: (total: Int) -> Unit,
        onMetaError: ((String) -> Unit)?,
        onProgress: (CombineResult) -> Unit,
    ) = combiner.combineAll(
        outputDir = outputDir,
        deleteOriginals = deleteOriginals,
        onStart = onStart,
        onMetaStart = onMetaStart,
        onMetaError = onMetaError,
        onProgress = onProgress,
    )
}

private fun androidAtomicNoOverwritePublish(staged: File, destination: File): PublishOutcome {
    return try {
        // Atomic in the kernel since API 21: fails with EEXIST if `destination` already
        // exists, closing the mid-combine TOCTOU overwrite window (#64).
        Os.link(staged.absolutePath, destination.absolutePath)
        staged.delete()
        PublishOutcome.Published
    } catch (e: ErrnoException) {
        when (e.errno) {
            OsConstants.EEXIST -> PublishOutcome.AlreadyExists
            // Emulated external / FAT / FUSE mounts often disallow hardlinks; fall back to a
            // synchronized check-then-rename so shared storage still works cleanly.
            OsConstants.EPERM,
            OsConstants.EACCES,
            OsConstants.ENOSYS,
            OsConstants.EOPNOTSUPP,
            OsConstants.EXDEV,
            -> StagedImageOverlayCombiner.jvmSynchronizedNoOverwritePublish(staged, destination)
            else -> PublishOutcome.Failed
        }
    }
}

private fun hasExifCaptureTimestamp(filePath: String): Boolean =
    runCatching {
        !ExifInterface(filePath).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrBlank()
    }.getOrDefault(false)
