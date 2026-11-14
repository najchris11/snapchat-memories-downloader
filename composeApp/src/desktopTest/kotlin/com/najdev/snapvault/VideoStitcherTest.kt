package com.najdev.snapvault

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class VideoStitcherTest {
    @Test
    fun compatibleClipsAreCopiedInOrderAndOriginalsAreKept() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("stitch-test").toFile()
        try {
            org.junit.Assume.assumeTrue("FFmpeg and FFprobe are required for real-process stitching tests",
                BinaryExtractor.checkCommand("ffmpeg") != null && BinaryExtractor.checkCommand("ffprobe") != null)
            val first = clip(dir, "first", "red", "64x64")
            val second = clip(dir, "second", "blue", "64x64")
            val before = listOf(first, second).map { it.readBytes().toList() }
            val result = stitchVideos(listOf(first.path, second.path), dir.path)
            assertIs<VideoStitchResult.Success>(result)
            assertTrue(File(result.outputPath).isFile)
            val probe = runCommand(listOf("ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", result.outputPath))
            assertEquals("20", probe.output.trim())
            fun hashes(file: File): List<String> = runCommand(listOf("ffmpeg", "-v", "error", "-i", file.path, "-f", "framemd5", "-")).output
                .lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringAfterLast(',').trim() }.toList()
            assertEquals(hashes(first) + hashes(second), hashes(File(result.outputPath)), "decoded frames must retain clip order and content")
            assertEquals(before, listOf(first, second).map { it.readBytes().toList() })
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun incompatibleClipsAreRejectedWithoutAnOutputOrSourceChanges() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("stitch-mismatch").toFile()
        try {
            org.junit.Assume.assumeTrue("FFmpeg and FFprobe are required for real-process stitching tests",
                BinaryExtractor.checkCommand("ffmpeg") != null && BinaryExtractor.checkCommand("ffprobe") != null)
            val first = clip(dir, "first", "red", "64x64")
            val second = clip(dir, "second", "blue", "128x64")
            assertEquals(VideoStitchResult.Incompatible, stitchVideos(listOf(first.path, second.path), dir.path))
            assertEquals(setOf("first.mp4", "second.mp4"), dir.list()!!.toSet())
        } finally { dir.deleteRecursively() }
    }

    private fun clip(dir: File, name: String, color: String, dimensions: String): File {
        val file = File(dir, "$name.mp4")
        val result = runCommand(listOf("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=$color:s=$dimensions:r=10:d=1", "-c:v", "libx264", "-pix_fmt", "yuv420p", file.path))
        assertEquals(0, result.exitCode, result.output)
        return file
    }
}
