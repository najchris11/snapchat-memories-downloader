package com.najdev.snapvault.metadata

import com.najdev.snapvault.BinaryExtractor
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class DesktopMediaProcessor : MediaProcessor {

    // Probed once on first video encode; null means fall back to libx264.
    private val hwEncoder: String? by lazy { detectHwEncoder() }

    private fun detectHwEncoder(): String? {
        val ffmpegPath = BinaryExtractor.checkCommand("ffmpeg") ?: return null
        // Priority: NVENC (NVIDIA) → VideoToolbox (macOS) → QSV (Intel) → VAAPI (Linux AMD/Intel) → AMF (Windows AMD)
        val candidates = listOf("h264_nvenc", "h264_videotoolbox", "h264_qsv", "h264_vaapi", "h264_amf")
        return try {
            val proc = ProcessBuilder(ffmpegPath, "-encoders")
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            candidates.firstOrNull { it in output }
        } catch (_: Exception) { null }
    }

    // VAAPI requires CPU frames to be uploaded to GPU memory after the filter graph runs.
    // All other hardware encoders accept yuv420p frames directly from the CPU filter graph.
    private fun isVaapi(encoder: String) = encoder == "h264_vaapi"

    private fun hwEncodeArgs(encoder: String): List<String> = when (encoder) {
        "h264_nvenc"       -> listOf("-c:v", "h264_nvenc",        "-preset", "p4",          "-cq",            "18", "-pix_fmt", "yuv420p")
        "h264_videotoolbox"-> listOf("-c:v", "h264_videotoolbox", "-q:v",    "65",                                  "-pix_fmt", "yuv420p")
        "h264_qsv"         -> listOf("-c:v", "h264_qsv",          "-global_quality", "18",                         "-pix_fmt", "nv12")
        "h264_vaapi"       -> listOf("-c:v", "h264_vaapi")
        "h264_amf"         -> listOf("-c:v", "h264_amf",          "-quality","balanced", "-qp_i", "18", "-qp_p", "18", "-pix_fmt", "yuv420p")
        else               -> listOf("-c:v", "libx264",            "-preset", "medium",   "-crf",            "18", "-pix_fmt", "yuv420p")
    }

    private fun softwareEncodeArgs() = hwEncodeArgs("libx264")

    override fun activeVideoEncoder(): String? = hwEncoder

    override fun checkExifTool(): Boolean {
        val path = BinaryExtractor.checkCommand("exiftool") ?: return false
        return try {
            val process = ProcessBuilder(path, "-ver").start()
            process.waitFor() == 0
        } catch (e: IOException) {
            false
        } catch (e: InterruptedException) {
            false
        }
    }

    override fun checkFFmpeg(): Boolean {
        val path = BinaryExtractor.checkCommand("ffmpeg") ?: return false
        return try {
            val process = ProcessBuilder(path, "-version").start()
            process.waitFor() == 0
        } catch (e: IOException) {
            false
        } catch (e: InterruptedException) {
            false
        }
    }

    private fun hasRiffSignature(file: File): Boolean = try {
        file.inputStream().use { s ->
            val h = ByteArray(4).also { s.read(it) }
            h[0] == 'R'.code.toByte() && h[1] == 'I'.code.toByte() &&
                h[2] == 'F'.code.toByte() && h[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) { false }

    //META date parsing: converts "YYYY-MM-DD HH:MM:SS" or "DD.MM.YYYY" → "YYYYMMDD_HHMMSS" for EXIF/filesystem
    private fun parseDateToFilenamePrefix(dateStr: String?): String? {
        if (dateStr == null) return null
        val cleaned = dateStr.trim()
        
        // Format 1: YYYY-MM-DD HH:MM:SS (UTC or similar) or YYYY-MM-DD
        val regex1 = Regex("""(\d{4})-(\d{2})-(\d{2})(?:\s+(\d{2}):(\d{2}):(\d{2}))?""")
        val match1 = regex1.find(cleaned)
        if (match1 != null) {
            val y = match1.groupValues[1]
            val m = match1.groupValues[2]
            val d = match1.groupValues[3]
            val h = match1.groupValues[4].takeIf { it.isNotEmpty() } ?: "00"
            val min = match1.groupValues[5].takeIf { it.isNotEmpty() } ?: "00"
            val s = match1.groupValues[6].takeIf { it.isNotEmpty() } ?: "00"
            return "${y}${m}${d}_${h}${min}${s}"
        }
        
        // Format 2: DD.MM.YYYY HH:MM:SS or DD.MM.YYYY
        val regex2 = Regex("""(\d{2})\.(\d{2})\.(\d{4})(?:\s+(\d{2}):(\d{2}):(\d{2}))?""")
        val match2 = regex2.find(cleaned)
        if (match2 != null) {
            val d = match2.groupValues[1]
            val m = match2.groupValues[2]
            val y = match2.groupValues[3]
            val h = match2.groupValues[4].takeIf { it.isNotEmpty() } ?: "00"
            val min = match2.groupValues[5].takeIf { it.isNotEmpty() } ?: "00"
            val s = match2.groupValues[6].takeIf { it.isNotEmpty() } ?: "00"
            return "${y}${m}${d}_${h}${min}${s}"
        }
        
        return null
    }

    //META date format: converts parsed prefix → "YYYY:MM:DD HH:MM:SS" as required by exiftool
    private fun formatToExifDate(dateStr: String?): String? {
        if (dateStr == null) return null
        val prefix = parseDateToFilenamePrefix(dateStr) ?: return null
        if (prefix.length >= 15) {
            val y = prefix.substring(0, 4)
            val m = prefix.substring(4, 6)
            val d = prefix.substring(6, 8)
            val h = prefix.substring(9, 11)
            val min = prefix.substring(11, 13)
            val s = prefix.substring(13, 15)
            return "$y:$m:$d $h:$min:$s"
        }
        return null
    }

    //META GPS write: sets GPSLatitude/Longitude tags + optional DateTimeOriginal/CreateDate via exiftool; also stamps filesystem mtime
    override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false

        val path = BinaryExtractor.checkCommand("exiftool") ?: return false

        val latRef = if (latitude >= 0) "N" else "S"
        val lonRef = if (longitude >= 0) "E" else "W"
        val absLat = Math.abs(latitude)
        val absLon = Math.abs(longitude)

        val args = mutableListOf(
            path,
            "-overwrite_original",
            "-q",
            "-GPSLatitude=$absLat",
            "-GPSLatitudeRef=$latRef",
            "-GPSLongitude=$absLon",
            "-GPSLongitudeRef=$lonRef"
        )

        val exifDate = formatToExifDate(dateStr)
        if (exifDate != null) {
            val ext = file.extension.lowercase()
            if (ext in setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "tiff", "tif")) {
                args.add("-DateTimeOriginal=$exifDate")
                args.add("-CreateDate=$exifDate")
                args.add("-ModifyDate=$exifDate")
            } else if (ext in setOf("mp4", "mov", "avi", "mkv", "m4v")) {
                args.add("-CreateDate=$exifDate")
                args.add("-MediaCreateDate=$exifDate")
                args.add("-TrackCreateDate=$exifDate")
                args.add("-ModifyDate=$exifDate")
            }
        }

        args.add(filePath)

        return try {
            val process = ProcessBuilder(args).start()
            val exitCode = process.waitFor()
            
            // Apply system modification date fallback
            if (exifDate != null) {
                // Try setting file system modification time
                val prefix = parseDateToFilenamePrefix(dateStr)
                if (prefix != null && prefix.length >= 15) {
                    try {
                        val ldt = LocalDateTime.parse(prefix, DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        file.setLastModified(ldt.toInstant(ZoneOffset.UTC).toEpochMilli())
                    } catch (_: Exception) {}
                }
            }
            
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    //META single-file date write: writes DateTimeOriginal/CreateDate via exiftool; returns false for unrecognized extensions (heic, webp, mkv, aac, etc.)
    override fun writeDateMetadata(filePath: String, dateTimeUtc: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false

        val path = BinaryExtractor.checkCommand("exiftool") ?: return false

        val exifDate = formatToExifDate(dateTimeUtc) ?: run {
            val dateOnly = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(dateTimeUtc)
            if (dateOnly != null) {
                "${dateOnly.groupValues[1]}:${dateOnly.groupValues[2]}:${dateOnly.groupValues[3]} 00:00:00"
            } else return false
        }

        val ext = file.extension.lowercase()
        val args = mutableListOf(path, "-overwrite_original", "-q")

        if (ext in setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "tiff", "tif")) {
            args += listOf("-DateTimeOriginal=$exifDate", "-CreateDate=$exifDate", "-ModifyDate=$exifDate")
        } else if (ext in setOf("mp4", "mov", "avi", "mkv", "m4v")) {
            args += listOf("-CreateDate=$exifDate", "-MediaCreateDate=$exifDate", "-TrackCreateDate=$exifDate", "-ModifyDate=$exifDate")
        } else {
            return false
        }

        args.add(filePath)

        return try {
            val process = ProcessBuilder(args).start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                val prefix = parseDateToFilenamePrefix(dateTimeUtc)
                if (prefix != null && prefix.length >= 15) {
                    try {
                        val ldt = LocalDateTime.parse(prefix, DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        file.setLastModified(ldt.toInstant(ZoneOffset.UTC).toEpochMilli())
                    } catch (_: Exception) {}
                }
            }
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    //META batch date write: groups files by extension (images vs videos), fires one exiftool per group; non-image/video files are silently skipped (count=0)
    //META on batch failure falls back to per-file so we can pinpoint which file is the culprit
    override fun writeDateMetadataBatch(
        filePaths: List<String>,
        dateOnly: String,
        onError: ((String) -> Unit)?,
    ): Int {
        if (filePaths.isEmpty()) return 0
        val exifPath = BinaryExtractor.checkCommand("exiftool")
            ?: return super.writeDateMetadataBatch(filePaths, dateOnly, onError)

        // exiftool expects "YYYY:MM:DD HH:MM:SS"
        val exifDate = "${dateOnly.replace("-", ":")} 00:00:00"

        val imageExts = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "tiff", "tif")
        val videoExts = setOf("mp4", "mov", "avi", "mkv", "m4v")
        val images = filePaths.filter { File(it).extension.lowercase() in imageExts }
        val videos = filePaths.filter { File(it).extension.lowercase() in videoExts }

        val epochMs = runCatching {
            LocalDate.parse(dateOnly).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()

        // Run one exiftool call for the given group. Returns the list of paths that succeeded.
        // Pre-filters missing files and RIFF-misnamed PNGs (both cause exiftool rc=1, poisoning
        // the whole batch). On batch failure for other reasons, falls back per-file.
        fun runGroup(paths: List<String>, tagArgs: List<String>): List<String> {
            val present = mutableListOf<String>()
            for (path in paths) {
                val f = File(path)
                when {
                    !f.exists() -> onError?.invoke("[metadata] file not found: ${f.name}")
                    // Snapchat stores some overlays as WebP but names them .png; exiftool rejects
                    // them as "not a valid PNG (looks more like a RIFF)" and fails the whole batch.
                    f.extension.lowercase() == "png" && hasRiffSignature(f) ->
                        onError?.invoke("[metadata] skipped RIFF-as-PNG (WebP mislabeled): ${f.name}")
                    else -> present.add(path)
                }
            }
            if (present.isEmpty()) return emptyList()

            val batchArgs = listOf(exifPath, "-overwrite_original", "-q") + tagArgs + present
            return runCatching {
                val proc = ProcessBuilder(batchArgs).redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                val rc = proc.waitFor()
                if (rc == 0) return present

                // Batch failed for another reason — surface output and retry per-file to pinpoint
                if (output.isNotBlank()) onError?.invoke("[exiftool batch rc=$rc] ${output.trim()}")
                val succeeded = mutableListOf<String>()
                for (path in present) {
                    val singleArgs = listOf(exifPath, "-overwrite_original", "-q") + tagArgs + listOf(path)
                    val singleProc = ProcessBuilder(singleArgs).redirectErrorStream(true).start()
                    val singleOut = singleProc.inputStream.bufferedReader().readText()
                    val singleRc = singleProc.waitFor()
                    if (singleRc == 0) {
                        succeeded.add(path)
                    } else {
                        val name = File(path).name
                        val reason = singleOut.trim().ifBlank { "exit $singleRc" }
                        onError?.invoke("[exiftool] $name — $reason")
                    }
                }
                succeeded
            }.getOrElse { e ->
                onError?.invoke("[exiftool] exception: ${e.message}")
                emptyList()
            }
        }

        var successCount = 0

        if (images.isNotEmpty()) {
            val tagArgs = listOf("-DateTimeOriginal=$exifDate", "-CreateDate=$exifDate", "-ModifyDate=$exifDate")
            val succeeded = runGroup(images, tagArgs)
            successCount += succeeded.size
            epochMs?.let { ms -> succeeded.forEach { File(it).setLastModified(ms) } }
        }

        if (videos.isNotEmpty()) {
            val tagArgs = listOf(
                "-CreateDate=$exifDate", "-MediaCreateDate=$exifDate",
                "-TrackCreateDate=$exifDate", "-ModifyDate=$exifDate"
            )
            val succeeded = runGroup(videos, tagArgs)
            successCount += succeeded.size
            epochMs?.let { ms -> succeeded.forEach { File(it).setLastModified(ms) } }
        }

        return successCount
    }

    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean {
        val ffmpegPath = BinaryExtractor.checkCommand("ffmpeg") ?: return false
        val exiftoolPath = BinaryExtractor.checkCommand("exiftool")

        // scale2ref scales the overlay (input 1) to the video's (input 0) dimensions.
        // shortest=1 stops the encode when the video ends (the PNG loops via -loop 1).
        val filterBase = "[1:v][0:v]scale2ref[ovr][base];[base][ovr]overlay=0:0:shortest=1:format=auto"

        fun buildArgs(encoder: String?): List<String> {
            // VAAPI needs frames uploaded from CPU memory to GPU after the filter graph runs.
            // Append format=nv12,hwupload to the filter chain and map the named output explicitly.
            return if (encoder != null && isVaapi(encoder)) {
                listOf(
                    ffmpegPath, "-y",
                    "-init_hw_device", "vaapi=va",
                    "-filter_hw_device", "va",
                    "-i", videoPath,
                    "-loop", "1", "-i", overlayPath,
                    "-filter_complex", "$filterBase,format=nv12,hwupload[vout]",
                    "-map", "[vout]", "-map", "0:a?",
                ) + hwEncodeArgs(encoder) + outputPath
            } else {
                val codecArgs = if (encoder != null) hwEncodeArgs(encoder) else softwareEncodeArgs()
                listOf(
                    ffmpegPath, "-y",
                    "-i", videoPath,
                    "-loop", "1", "-i", overlayPath,
                    "-filter_complex", filterBase,
                    "-c:a", "copy",
                ) + codecArgs + outputPath
            }
        }

        // Discard stdout+stderr — FFmpeg writes verbose progress to stderr and the pipe buffer
        // (~64 KB on macOS) fills up for long videos, causing waitFor() to block forever.
        fun runEncode(args: List<String>): Int =
            ProcessBuilder(args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor()

        val encoder = hwEncoder
        var exitCode = runEncode(buildArgs(encoder))

        if (exitCode != 0 && encoder != null) {
            // Hardware encoder failed (driver issue, unsupported format, etc.) — retry with libx264.
            System.err.println("[hwaccel] $encoder failed for ${File(videoPath).name}, retrying with libx264")
            File(outputPath).delete()
            exitCode = runEncode(buildArgs(null))
        }

        if (exitCode == 0 && exiftoolPath != null) {
            try {
                val metaProc = ProcessBuilder(
                    exiftoolPath,
                    "-overwrite_original", "-q",
                    "-TagsFromFile", videoPath,
                    "-all:all",
                    outputPath
                ).redirectErrorStream(true).start()
                val metaOut = metaProc.inputStream.bufferedReader().readText()
                val metaRc = metaProc.waitFor()
                if (metaRc != 0 && metaOut.isNotBlank()) {
                    System.err.println("[exiftool metadata copy rc=$metaRc] ${File(outputPath).name}: ${metaOut.trim()}")
                }
            } catch (e: Exception) {
                System.err.println("[exiftool metadata copy] ${File(outputPath).name}: ${e.message}")
            }
        }

        return exitCode == 0
    }
}
