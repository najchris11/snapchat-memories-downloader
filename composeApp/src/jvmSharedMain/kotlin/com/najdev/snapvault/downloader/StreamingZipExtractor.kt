package com.najdev.snapvault.downloader

import com.najdev.snapvault.StreamingZipReader
import com.najdev.snapvault.parser.HtmlMemoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Single archive pass, staged individual media, and no writes to the source document. */
class StreamingZipExtractor(private val open: (String) -> InputStream) {
    suspend fun extract(
        source: String,
        entries: List<HtmlMemoryEntry>,
        outputDir: String,
        onProgress: (ExtractResult) -> Unit,
    ) = withContext(Dispatchers.IO) {
        data class Target(val name: String, val uuid: String)
        val pending = linkedMapOf<String, Target>()
        entries.forEach { entry ->
            pending["memories/${entry.archiveFileName}"] = Target(entry.fileName, entry.uuid)
            val archiveOverlay = entry.archiveOverlayFileName
            val overlay = entry.overlayFileName
            if (entry.hasOverlay && archiveOverlay != null && overlay != null) {
                pending["memories/$archiveOverlay"] = Target(overlay, entry.uuid)
            }
        }
        val folder = File(outputDir).apply { mkdirs() }
        val context = currentCoroutineContext()
        // Stage inside our own directory, not beside finished media: a process killed mid-entry
        // otherwise left a `.part` in the library that nothing ever removed. openStaging clears
        // the previous run's leftovers.
        val staging = openStaging(folder)
        try {
            StreamingZipReader { open(source) }.forEachEntry { entry, input ->
                context.ensureActive()
                val target = pending.remove(entry.name) ?: return@forEachEntry
                val destination = File(folder, target.name)
                if (target.name != destination.name || target.name in setOf(".", "..") || '\\' in target.name) {
                    onProgress(ExtractResult(target.uuid, target.name, "", false, "Unsafe archive filename"))
                    return@forEachEntry
                }
                if (destination.exists()) {
                    val complete = destination.isFile && destination.length() > 0
                    onProgress(ExtractResult(target.uuid, target.name, destination.path, complete,
                        if (complete) null else "Destination already exists but is empty or not a file"))
                    return@forEachEntry
                }
                var staged: File? = null
                try {
                    val temporary = File.createTempFile(".snapvault-stream-", ".part", staging)
                    staged = temporary
                    var written = 0L
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            written += count
                        }
                    }
                    if (entry.size >= 0 && written != entry.size) throw IOException("Incomplete ZIP entry")
                    when (StagedImageOverlayCombiner.jvmSynchronizedNoOverwritePublish(temporary, destination)) {
                        PublishOutcome.Published -> onProgress(ExtractResult(target.uuid, target.name, destination.path, false, null))
                        PublishOutcome.AlreadyExists -> onProgress(ExtractResult(target.uuid, target.name, destination.path, true, null))
                        PublishOutcome.Failed -> throw IOException("Could not publish extracted file")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onProgress(ExtractResult(target.uuid, target.name, "", false, e.message ?: "ZIP extraction failed"))
                } finally {
                    staged?.delete()
                }
            }
            pending.values.forEach { target -> onProgress(ExtractResult(target.uuid, target.name, "", false, "Entry not found in ZIP")) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pending.values.forEach { target -> onProgress(ExtractResult(target.uuid, target.name, "", false, e.message ?: "Could not read ZIP document")) }
        } finally {
            closeStaging(staging)
        }
    }
}
