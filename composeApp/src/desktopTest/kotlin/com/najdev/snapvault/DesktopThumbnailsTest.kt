package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** D19: the Library's thumbnail cache. */
class DesktopThumbnailsTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("snapvault-thumbs").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val cacheDir get() = File(dir, ".thumbnails")

    private fun solidPng(name: String, color: Color): File {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { this.color = color; fillRect(0, 0, 64, 64); dispose() }
        return File(dir, name).also { ImageIO.write(image, "png", it) }
    }

    private fun ImageBitmap.centre(): androidx.compose.ui.graphics.Color = toPixelMap()[width / 2, height / 2]

    /** A thumbnailer whose "ffmpeg" writes a small real JPEG to the output argument. */
    private fun fakeFfmpegThatWorks(calls: MutableList<List<String>>) = DesktopThumbnails(
        ffmpegPath = { "ffmpeg" },
        runFfmpeg = { args, _ ->
            calls += args
            val out = File(args.last())
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply { color = Color.GREEN; fillRect(0, 0, 32, 32); dispose() }
            ImageIO.write(image, "jpg", out)
            CommandResult(0, "")
        },
    )

    // D19: the cache accepted any file at `.thumbnails/<name>.jpg` with no check against the
    // source. Replace a photo under the same name — a re-export, an edit, a combine that
    // rewrote it — and the Library kept showing the old picture indefinitely.
    @Test
    fun overwritingASourceRegeneratesItsThumbnail() {
        val source = solidPng("2024-06-01_AAA.png", Color.RED)
        val first = DesktopThumbnails(ffmpegPath = { null }).load(source.absolutePath)
        assertNotNull(first)
        assertTrue(first.centre().red > 0.8f, "fixture sanity: the first thumbnail is red")

        solidPng("2024-06-01_AAA.png", Color.BLUE)
        source.setLastModified(source.lastModified() + 5_000)

        val second = DesktopThumbnails(ffmpegPath = { null }).load(source.absolutePath)
        assertNotNull(second)
        assertTrue(second.centre().blue > 0.8f, "served the thumbnail of the file that was replaced")
    }

    // A cached thumbnail that will not decode — truncated by a crash, damaged on disk — was
    // returned as null on every load, and never regenerated: a permanently blank tile.
    @Test
    fun anUnreadableCachedThumbnailIsRegeneratedNotServedBlankForever() {
        val source = solidPng("2024-06-01_AAA.png", Color.RED)
        assertNotNull(DesktopThumbnails(ffmpegPath = { null }).load(source.absolutePath))
        val cached = cacheDir.listFiles().orEmpty().filter { it.isFile }
        assertTrue(cached.isNotEmpty(), "fixture sanity: a thumbnail was cached")
        cached.forEach { it.writeText("not a jpeg") }

        assertNotNull(DesktopThumbnails(ffmpegPath = { null }).load(source.absolutePath))
    }

    // The video path waited, destroyed the process on timeout, and returned — leaving
    // whatever ffmpeg had half-written under the final cache name, where the next load
    // trusted it. A timed-out or failed generation must leave nothing behind.
    @Test
    fun aTimedOutGenerationLeavesNoPartialThumbnail() {
        val source = File(dir, "2024-06-01_AAA.mp4").apply { writeText("video") }
        val thumbnails = DesktopThumbnails(
            ffmpegPath = { "ffmpeg" },
            runFfmpeg = { args, timeout ->
                File(args.last()).writeText("half a jpeg")
                throw CommandTimeoutException("ffmpeg", timeout)
            },
        )

        assertNull(thumbnails.load(source.absolutePath))
        assertEquals(emptyList(), cacheDir.listFiles().orEmpty().map { it.name }, "partial output was left in the cache")
    }

    // The exit code was never checked either. ffmpeg can fail after writing a frame that
    // decodes perfectly well — a garbled or wrong one — so decoding is no substitute for asking
    // whether the run succeeded.
    @Test
    fun aFailedGenerationLeavesNoPartialThumbnail() {
        val source = File(dir, "2024-06-01_AAA.mp4").apply { writeText("video") }
        val thumbnails = DesktopThumbnails(
            ffmpegPath = { "ffmpeg" },
            runFfmpeg = { args, _ ->
                val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
                ImageIO.write(image, "jpg", File(args.last()))
                CommandResult(1, "Invalid data found when processing input")
            },
        )

        assertNull(thumbnails.load(source.absolutePath))
        assertEquals(emptyList(), cacheDir.listFiles().orEmpty().map { it.name })
    }

    // The Library lists HEIC, WebP, MKV and M4V, but the thumbnailer only tried mp4/mov/gif
    // and jpg/jpeg/png. Everything else fell through to "no thumbnail", silently, however
    // capable the installed ffmpeg was.
    @Test
    fun everyFormatTheLibraryListsGetsAThumbnailAttempt() {
        for (name in listOf("a.heic", "b.webp", "c.mkv", "d.m4v", "e.avi", "f.tiff")) {
            val source = File(dir, name).apply { writeText("media") }
            val calls = mutableListOf<List<String>>()

            val bitmap = fakeFfmpegThatWorks(calls).load(source.absolutePath)

            assertNotNull(bitmap, "$name got no thumbnail")
            assertEquals(1, calls.size, "$name was never handed to ffmpeg")
        }
    }

    // Companion: without ffmpeg, a format ImageIO cannot read is simply unavailable — no
    // crash and no litter.
    @Test
    fun withoutFfmpegAnUnreadableFormatIsUnavailableAndLeavesNothing() {
        val source = File(dir, "a.heic").apply { writeText("media") }

        assertNull(DesktopThumbnails(ffmpegPath = { null }).load(source.absolutePath))
        assertTrue(cacheDir.listFiles().orEmpty().none { it.isFile }, "stray cache files: ${cacheDir.listFiles()?.map { it.name }}")
    }

    // The in-memory cache in front of the disk cache was keyed by path alone, so within one
    // session a replaced file kept its old thumbnail even once the disk cache was right.
    @Test
    fun theInMemoryCacheAlsoNoticesAReplacedSource() {
        ThumbnailCache.clear()
        val source = solidPng("2024-06-01_BBB.png", Color.RED)
        val first = getCachedThumbnail(source.absolutePath)
        assertNotNull(first)

        solidPng("2024-06-01_BBB.png", Color.BLUE)
        source.setLastModified(source.lastModified() + 5_000)

        val second = getCachedThumbnail(source.absolutePath) ?: fail("no thumbnail")
        assertTrue(second.centre().blue > 0.8f, "the in-memory cache served the replaced file's thumbnail")
        ThumbnailCache.clear()
    }
}
