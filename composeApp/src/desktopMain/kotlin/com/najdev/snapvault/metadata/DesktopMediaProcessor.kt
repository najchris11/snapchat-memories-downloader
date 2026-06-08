package com.najdev.snapvault.metadata

import com.najdev.snapvault.BinaryExtractor
import java.io.File
import java.io.IOException

class DesktopMediaProcessor : MediaProcessor {

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
            if (ext in listOf("jpg", "jpeg", "png")) {
                args.add("-DateTimeOriginal=$exifDate")
                args.add("-CreateDate=$exifDate")
                args.add("-ModifyDate=$exifDate")
            } else if (ext in listOf("mp4", "mov", "avi")) {
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
                        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                        val date = sdf.parse(prefix)
                        file.setLastModified(date.time)
                    } catch (_: Exception) {}
                }
            }
            
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

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

        if (ext in listOf("jpg", "jpeg", "png")) {
            args += listOf("-DateTimeOriginal=$exifDate", "-CreateDate=$exifDate", "-ModifyDate=$exifDate")
        } else if (ext in listOf("mp4", "mov", "avi")) {
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
                        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                        val date = sdf.parse(prefix)
                        file.setLastModified(date.time)
                    } catch (_: Exception) {}
                }
            }
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String): Boolean {
        val ffmpegPath = BinaryExtractor.checkCommand("ffmpeg") ?: return false
        val exiftoolPath = BinaryExtractor.checkCommand("exiftool")
        
        // scale2ref scales the overlay (input 1) to the video's (input 0) dimensions.
        // shortest=1 stops the encode when the video ends (the PNG loops via -loop 1).
        val filterComplex = "[1:v][0:v]scale2ref[ovr][base];[base][ovr]overlay=0:0:shortest=1:format=auto"
        val args = listOf(
            ffmpegPath,
            "-y",
            "-i", videoPath,
            "-loop", "1", "-i", overlayPath,   // -loop 1: repeat PNG for all video frames
            "-filter_complex", filterComplex,
            "-c:a", "copy",
            "-c:v", "libx264",
            "-preset", "medium",
            "-crf", "18",
            "-pix_fmt", "yuv420p",
            outputPath
        )

        return try {
            val process = ProcessBuilder(args).start()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                // Copy EXIF metadata if exiftool is available
                if (exiftoolPath != null) {
                    try {
                        val metaProcess = ProcessBuilder(
                            exiftoolPath,
                            "-overwrite_original",
                            "-q",
                            "-TagsFromFile", videoPath,
                            "-all:all",
                            outputPath
                        ).start()
                        metaProcess.waitFor()
                    } catch (_: Exception) {}
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
