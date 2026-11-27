package com.najdev.snapvault

import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.math.abs

actual val supportsVideoStitching: Boolean = true

actual suspend fun stitchVideos(paths: List<String>, outputDir: String): VideoStitchResult =
    withContext(ioDispatcher) { runInterruptibleCompat { stitchOnDesktop(paths, outputDir) } }

private val streamKeys = setOf(
    "codec_type", "codec_name", "codec_tag_string", "profile", "width", "height", "pix_fmt",
    "time_base", "r_frame_rate", "sample_rate", "channels", "channel_layout", "extradata_hash",
)

private data class ClipProbe(val streams: List<Map<String, JsonElement>>, val duration: Double)

private fun probeClip(ffprobe: String, path: String): ClipProbe? {
    val probe = runCommand(listOf(ffprobe, "-v", "error", "-show_streams", "-show_format", "-show_data_hash", "sha256", "-of", "json", path), 30_000)
    if (probe.exitCode != 0) return null
    val json = Json.parseToJsonElement(probe.output).jsonObject
    val streams = json.getValue("streams").jsonArray.map { stream -> stream.jsonObject.filterKeys { it in streamKeys } }
    if (streams.none { it["codec_type"]?.jsonPrimitive?.content == "video" }) return null
    if (streams.any { it["codec_type"]?.jsonPrimitive?.content !in setOf("video", "audio") }) return null
    val duration = json.getValue("format").jsonObject["duration"]?.jsonPrimitive?.doubleOrNull ?: return null
    if (!duration.isFinite() || duration <= 0) return null
    return ClipProbe(streams, duration)
}

private fun stitchOnDesktop(paths: List<String>, outputDir: String): VideoStitchResult {
    if (paths.size < 2 || paths.distinct().size != paths.size) return VideoStitchResult.InvalidSelection
    val sources = paths.map(::File)
    if (sources.any { !it.isFile || '\n' in it.path || '\r' in it.path }) return VideoStitchResult.InvalidSelection
    val directory = File(outputDir)
    if (!directory.isDirectory) return VideoStitchResult.InvalidSelection
    val ffmpeg = BinaryExtractor.checkCommand("ffmpeg") ?: return VideoStitchResult.ToolsMissing
    val ffprobe = BinaryExtractor.checkCommand("ffprobe") ?: return VideoStitchResult.ToolsMissing
    var lock: OutputDirectoryLock? = null
    var staging: File? = null
    return try {
        val probes = sources.map { probeClip(ffprobe, it.absolutePath) ?: return VideoStitchResult.Incompatible }
        if (probes.any { it.streams != probes.first().streams }) return VideoStitchResult.Incompatible
        lock = platformOutputDirectoryLocker.lock(outputDir) {}
        staging = Files.createTempDirectory(directory.toPath(), ".snapvault-stitch-").toFile()
        val manifest = File(staging, "clips.txt")
        manifest.writeText(sources.joinToString("\n", prefix = "ffconcat version 1.0\n") {
            "file '${it.absolutePath.replace('\\', '/').replace("'", "'\\''")}'"
        })
        val candidate = File(staging, "output.mp4")
        val command = runCommand(listOf(ffmpeg, "-v", "error", "-nostdin", "-f", "concat", "-safe", "0", "-protocol_whitelist", "file,pipe", "-i", manifest.path, "-map", "0", "-c", "copy", "-map_metadata", "0", "-movflags", "+faststart", candidate.path))
        if (command.exitCode != 0 || candidate.length() == 0L) return VideoStitchResult.Failed
        val outputProbe = probeClip(ffprobe, candidate.path) ?: return VideoStitchResult.Failed
        if (abs(outputProbe.duration - probes.sumOf { it.duration }) > 0.25) return VideoStitchResult.Failed
        val datePrefix = Regex("^\\d{4}-\\d{2}-\\d{2}_").find(sources.first().name)?.value.orEmpty()
        val output = File(directory, "${datePrefix}stitched-${UUID.randomUUID()}.mp4")
        // A hard link publishes the complete verified file atomically without replacing a
        // concurrent file. Unsupported filesystems fail safely; source clips are never touched.
        Files.createLink(output.toPath(), candidate.toPath())
        VideoStitchResult.Success(output.absolutePath)
    } catch (e: InterruptedException) {
        throw e
    } catch (_: Exception) {
        VideoStitchResult.Failed
    } finally {
        staging?.deleteRecursively()
        lock?.release()
    }
}
