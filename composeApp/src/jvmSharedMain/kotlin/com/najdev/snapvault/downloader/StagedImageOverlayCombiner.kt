package com.najdev.snapvault.downloader

import com.najdev.snapvault.metadata.MediaProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

enum class PublishOutcome {
    Published,
    AlreadyExists,
    Failed,
}

/**
 * Staged image-overlay combiner shared on JVM targets and used by `AndroidZipPipelineRunner`.
 *
 * Addresses two Android data-safety and UX gaps while keeping the orchestration unit-testable
 * under `:composeApp:desktopTest`:
 * - **Issue #64**: Composites into `.snapvault-staging` first and publishes via [publishNoOverwrite]
 *   (backed on Android by `android.system.Os.link` since API 21) so a destination created
 *   mid-combine is never overwritten and its source `-main` / `-overlay` pair is preserved.
 * - **Issue #51**: Tracks combined outputs whose EXIF tags still lack a capture timestamp,
 *   calls `onMetaStart(count)`, and batches `YYYY-MM-DD` filename fallback writes through
 *   [MediaProcessor.writeDateMetadataBatch] with `onMetaError` wired.
 */
class StagedImageOverlayCombiner(
    private val mediaProcessor: MediaProcessor,
    private val publishNoOverwrite: (staged: File, destination: File) -> PublishOutcome = ::jvmSynchronizedNoOverwritePublish,
    private val hasCaptureTimestamp: (filePath: String) -> Boolean = { true },
    /**
     * Burns an overlay into an MP4 video, writing to the staged path it is given. Null keeps
     * videos skipped. A combined video never deletes its originals and never claims to carry
     * their metadata: nothing verifies that the encoder preserved it.
     */
    private val videoEncoder: (suspend (main: String, overlay: String, stagedOutput: String) -> Boolean)? = null,
) {

    suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        onStart: (total: Int) -> Unit = {},
        onMetaStart: (total: Int) -> Unit = {},
        onMetaError: ((String) -> Unit)? = null,
        onProgress: (CombineResult) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val dir = File(outputDir)
        if (!dir.isDirectory) {
            onStart(0)
            return@withContext
        }

        val byName = (dir.listFiles() ?: emptyArray()).filter { it.isFile }.associateBy { it.name }
        val pairs = findOverlayPairNames(byName.keys.toList())
        onStart(pairs.size)
        if (pairs.isEmpty()) return@withContext

        val stagingDir = openStaging(dir)
        val needsDateFallback = ArrayList<Pair<File, String>>()
        try {
            // Sequential by design on Android: two simultaneous full-resolution ARGB_8888
            // decodes + mutable canvas copy would push ~150 MB of bitmap heap against mobile
            // limits.
            for (pair in pairs) {
                coroutineContext.ensureActive()
                val mainFile = byName[pair.mainName] ?: continue
                val overlayFile = byName[pair.overlayName] ?: continue
                val outputFile = File(dir, pair.outputName)
                val result = combineOne(
                    pair = pair,
                    mainFile = mainFile,
                    overlayFile = overlayFile,
                    outputFile = outputFile,
                    stagingDir = stagingDir,
                    deleteOriginals = deleteOriginals,
                )
                // ExifInterface cannot write MP4, so videos are left out of the date fallback.
                if (result.status == "combined" && !pair.isVideo && !hasCaptureTimestamp(outputFile.absolutePath)) {
                    DATE_PREFIX_REGEX.find(mainFile.name)?.groupValues?.get(1)?.let { date ->
                        needsDateFallback += outputFile to date
                    }
                }
                onProgress(result)
            }
        } finally {
            closeStaging(stagingDir)
        }

        if (needsDateFallback.isNotEmpty()) {
            onMetaStart(needsDateFallback.size)
            for ((date, items) in needsDateFallback.groupBy({ it.second }, { it.first.absolutePath })) {
                coroutineContext.ensureActive()
                mediaProcessor.writeDateMetadataBatch(items, date, onMetaError)
            }
        }
    }

    private suspend fun combineOne(
        pair: OverlayPairNames,
        mainFile: File,
        overlayFile: File,
        outputFile: File,
        stagingDir: File,
        deleteOriginals: Boolean,
    ): CombineResult {
        val warnings = mutableListOf<String>()
        val preflightSkip = overlayCombineSkipStatus(
            pair,
            outputExists = outputFile.exists(),
            supportsVideo = videoEncoder != null && supportsMp4VideoOverlayContainer(pair.outputName),
        )
        if (preflightSkip != null) {
            return overlayCombineResult(
                pair = pair,
                mainPath = mainFile.absolutePath,
                overlayPath = overlayFile.absolutePath,
                outputPath = outputFile.absolutePath,
                status = preflightSkip,
                warnings = warnings,
            )
        }

        val stagedFile = File(stagingDir, "combine-${stagingCounter.incrementAndGet()}-${outputFile.name}")
        val encoded = try {
            if (pair.isVideo) {
                val encode = checkNotNull(videoEncoder)
                encode(mainFile.absolutePath, overlayFile.absolutePath, stagedFile.absolutePath)
            } else {
                mediaProcessor.combineImageWithOverlay(
                    mainPath = mainFile.absolutePath,
                    overlayPath = overlayFile.absolutePath,
                    outputPath = stagedFile.absolutePath,
                    onWarning = { warnings += it },
                )
            }
        } catch (e: CancellationException) {
            stagedFile.delete()
            throw e
        } catch (e: Exception) {
            stagedFile.delete()
            warnings += "Could not combine overlay for ${mainFile.name}: ${e.message}"
            false
        }

        val status = if (!encoded || !stagedFile.isFile || stagedFile.length() == 0L) {
            stagedFile.delete()
            OverlayCombineStatus.Failed
        } else {
            when (publishNoOverwrite(stagedFile, outputFile)) {
                PublishOutcome.Published -> OverlayCombineStatus.Combined
                PublishOutcome.AlreadyExists -> {
                    stagedFile.delete()
                    OverlayCombineStatus.SkippedExistingOutput
                }
                PublishOutcome.Failed -> {
                    stagedFile.delete()
                    warnings += "Could not publish combined file ${outputFile.name}"
                    OverlayCombineStatus.Failed
                }
            }
        }

        if (status == OverlayCombineStatus.Combined && pair.isVideo) warnings += VIDEO_METADATA_NOT_VERIFIED_WARNING

        val provisional = overlayCombineResult(
            pair = pair,
            mainPath = mainFile.absolutePath,
            overlayPath = overlayFile.absolutePath,
            outputPath = outputFile.absolutePath,
            status = status,
            warnings = warnings,
        )

        // Never delete originals if metadata failed to carry onto the composite, or the user
        // permanently loses the source's GPS/date tags.
        if (mayDeleteOriginals(status, deleteOriginals) && provisional.metadataCarried && !pair.isVideo) {
            val postWarnings = provisional.warnings.toMutableList()
            if (!mainFile.delete()) postWarnings += "could not delete original: ${mainFile.name}"
            if (!overlayFile.delete()) postWarnings += "could not delete overlay: ${overlayFile.name}"
            return provisional.copy(warnings = postWarnings)
        }

        return provisional
    }

    companion object {
        private val DATE_PREFIX_REGEX = Regex("""^(\d{4}-\d{2}-\d{2})""")
        private val stagingCounter = AtomicLong(0L)
        private val fallbackPublishLock = Any()

        fun jvmSynchronizedNoOverwritePublish(staged: File, destination: File): PublishOutcome =
            synchronized(fallbackPublishLock) {
                if (destination.exists()) return PublishOutcome.AlreadyExists
                if (staged.renameTo(destination)) return PublishOutcome.Published
                return try {
                    staged.copyTo(destination, overwrite = false)
                    staged.delete()
                    PublishOutcome.Published
                } catch (_: FileAlreadyExistsException) {
                    PublishOutcome.AlreadyExists
                } catch (_: Exception) {
                    if (destination.exists() && destination.length() == 0L) destination.delete()
                    PublishOutcome.Failed
                }
            }
    }
}
