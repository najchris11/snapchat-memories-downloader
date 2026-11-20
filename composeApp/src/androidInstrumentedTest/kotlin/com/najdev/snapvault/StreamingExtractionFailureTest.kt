package com.najdev.snapvault

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.najdev.snapvault.downloader.ExtractResult
import com.najdev.snapvault.downloader.StreamingZipExtractor
import com.najdev.snapvault.parser.HtmlMemoryEntry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Random
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Failure and cancellation of the production streaming extractor against a real ContentResolver pipe.
 *
 * The documents come from [PipeZipTestProvider] in the test APK. The extractor, reader and
 * [AndroidDocumentAccess] are the production classes; nothing here stubs the pipe. The only
 * decorator is a byte counter around the real stream, used to trigger a cancel at a precise
 * point in the entry being staged. These tests are bounded and synthetic: they say nothing about
 * any specific cloud provider's latency or behaviour.
 */
@RunWith(AndroidJUnit4::class)
class StreamingExtractionFailureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var folder: File
    private lateinit var cacheBefore: Set<String>

    @Before
    fun prepare() {
        cacheBefore = cacheFiles()
        folder = File(context.cacheDir, "stream-failure-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private fun cacheFiles() = context.cacheDir.walkTopDown().filter { it.isFile }.map { it.path }.toSet()
    private fun uri(name: String) = "content://${instrumentation.context.packageName}.pipezip/document/$name"
    private fun entry(name: String) = HtmlMemoryEntry(name, "uuid-$name", "2024-01-01", false, false, null)
    private val requested = listOf(entry("first.jpg"), entry("second.jpg"), entry("third.jpg"))
    private fun expected(seed: Int, size: Int) = ByteArray(size).also { Random(seed.toLong()).nextBytes(it) }
    private fun names() = folder.list().orEmpty().sorted()
    // In-progress entries live in the shared staging directory, never beside finished media.
    private fun staged(): List<String> {
        check(names().none { it.endsWith(".part") }) { "a .part appeared in the library folder: ${names()}" }
        return File(folder, ".snapvault-staging").list().orEmpty().filter { it.endsWith(".part") }.sorted()
    }
    private fun openDocument(path: String): InputStream =
        requireNotNull(context.contentResolver.openInputStream(Uri.parse(path))) { "provider returned no stream" }

    private suspend fun extract(name: String, open: (String) -> InputStream = ::openDocument): List<ExtractResult> {
        val results = CopyOnWriteArrayList<ExtractResult>()
        StreamingZipExtractor(open).extract(uri(name), requested, folder.path, results::add)
        return results
    }

    /** Counts what the extractor pulls from the real provider stream and reports each read. */
    private class Counting(source: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(source) {
        @Volatile var total = 0L
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also {
            if (it > 0) { total += it; onRead(total) }
        }
        override fun read(): Int = super.read().also { if (it >= 0) { total++; onRead(total) } }
    }

    @Test(timeout = 30_000)
    fun aCompleteProviderDocumentPublishesEveryEntryWithoutAStageOrACachedArchive() = runBlocking {
        val results = extract(LARGE)
        assertEquals(listOf("first.jpg", "second.jpg", "third.jpg"), results.map { it.fileName })
        assertTrue(results.all { it.error == null && !it.skipped })
        assertEquals(listOf("first.jpg", "second.jpg", "third.jpg"), names())
        assertTrue(expected(2, SECOND_SIZE).contentEquals(File(folder, "second.jpg").readBytes()))
        assertEquals(cacheBefore + folder.walkTopDown().filter { it.isFile }.map { it.path }, cacheFiles())
    }

    @Test(timeout = 30_000)
    fun aProviderThatClosesMidEntryFailsThatEntryAndLeavesNoStageOrPartialFile() = runBlocking {
        // Regression/edge: truncation inside entry two. Entry one was complete and stays; entry two
        // must be reported failed (never published); entry three never arrived so it fails too.
        val results = extract(TRUNCATED)
        assertEquals(listOf("first.jpg", "second.jpg", "third.jpg"), results.map { it.fileName }.sorted())
        assertTrue(results.single { it.fileName == "first.jpg" }.let { it.error == null && !it.skipped })
        for (name in listOf("second.jpg", "third.jpg")) {
            val failure = results.single { it.fileName == name }
            assertTrue("$name should fail", failure.error != null)
            assertEquals("", failure.outputPath)
        }
        assertEquals(listOf("first.jpg"), names())
        assertTrue(expected(1, FIRST_SIZE).contentEquals(File(folder, "first.jpg").readBytes()))
        assertReopensWithSameOutcome(TRUNCATED)
    }

    @Test(timeout = 30_000)
    fun aProviderThatReportsAnErrorMidEntryNeverPublishesTheStalledEntry() = runBlocking {
        val results = extract(FAULTY)
        assertTrue(results.single { it.fileName == "first.jpg" }.let { it.error == null && !it.skipped })
        assertTrue(results.single { it.fileName == "second.jpg" }.error != null)
        assertTrue(results.single { it.fileName == "third.jpg" }.error != null)
        assertEquals(listOf("first.jpg"), names())
        assertReopensWithSameOutcome(FAULTY)
    }

    @Test(timeout = 30_000)
    fun aDocumentTheProviderCannotOpenFailsEveryRequestedEntryAndWritesNothing() = runBlocking {
        val results = extract("missing.zip")
        assertEquals(listOf("first.jpg", "second.jpg", "third.jpg"), results.map { it.fileName }.sorted())
        assertTrue(results.all { it.error != null && !it.skipped && it.outputPath == "" })
        assertEquals(emptyList<String>(), names())
    }

    /** The source is still usable: reopening it re-runs the same extraction and keeps finished files. */
    private suspend fun assertReopensWithSameOutcome(name: String) {
        val again = extract(name)
        assertTrue("finished entry is kept, not rewritten", again.single { it.fileName == "first.jpg" }.skipped)
        assertTrue(again.single { it.fileName == "second.jpg" }.error != null)
        assertEquals(listOf("first.jpg"), names())
        val header = openDocument(uri(name)).use { stream -> ByteArray(4).also { stream.read(it) } }
        assertEquals(listOf(0x50, 0x4B, 3, 4), header.map { it.toInt() and 0xFF })
    }

    @Test(timeout = 30_000)
    fun cancellingWhileAnEntryIsBeingStagedStopsAtTheNextReadAndRemovesTheStage() = runBlocking {
        // Cooperative boundary: the extractor checks for cancellation before each read of the entry.
        // Regression guard: without that check entry two (1 MiB) would finish and be published.
        lateinit var job: Job
        var stagedWhenCancelled: List<String>? = null
        val results = CopyOnWriteArrayList<ExtractResult>()
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            StreamingZipExtractor { path ->
                Counting(openDocument(path)) { total ->
                    if (total >= CANCEL_AFTER_BYTES && stagedWhenCancelled == null) {
                        stagedWhenCancelled = staged()
                        job.cancel()
                    }
                }
            }.extract(uri(LARGE), requested, folder.path, results::add)
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("a stage must be active when the cancel arrives", 1, stagedWhenCancelled?.size)
        assertNoSuccessFor(results, "second.jpg", "third.jpg")
        assertEquals(listOf("first.jpg"), names())
        delay(500)
        assertEquals("nothing may appear after the extractor returned", listOf("first.jpg"), names())
        assertEquals(cacheBefore + folder.walkTopDown().filter { it.isFile }.map { it.path }, cacheFiles())
    }

    @Test(timeout = 30_000)
    fun cancellingBetweenEntriesNeverStartsAStageForTheNextEntry() = runBlocking {
        lateinit var job: Job
        val results = CopyOnWriteArrayList<ExtractResult>()
        var stagedAfterFirst: List<String>? = null
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            StreamingZipExtractor(::openDocument).extract(uri(LARGE), requested, folder.path) { result ->
                results.add(result)
                // Entry one is published and its stage is already removed when this fires.
                stagedAfterFirst = staged()
                job.cancel()
            }
        }
        job.start()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(emptyList<String>(), stagedAfterFirst)
        assertEquals(listOf("first.jpg"), results.map { it.fileName })
        assertEquals(listOf("first.jpg"), names())
    }

    @Test(timeout = 30_000)
    fun cancellingWhileTheProviderIsStalledIsHonouredAfterTheBlockedReadReturns() = runBlocking {
        // A read blocked inside the provider pipe is not interrupted by cancel(); the extractor
        // observes it at the next checked boundary once bytes arrive. This asserts the outcome
        // (cancelled, no publication, no stage, no late output), and reports the observed latency
        // without asserting it: it depends on the provider's stall, not on the extractor.
        lateinit var job: Job
        val counting = arrayOfNulls<Counting>(1)
        val results = CopyOnWriteArrayList<ExtractResult>()
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            StreamingZipExtractor { path -> Counting(openDocument(path)) {}.also { counting[0] = it } }
                .extract(uri(STALLED), requested, folder.path, results::add)
        }
        job.start()
        while (job.isActive && (counting[0]?.total ?: 0L) < CANCEL_AFTER_BYTES) delay(10)
        delay(300) // the provider has written its prefix and is now sleeping; the reader is blocked
        val stagedWhenCancelled = staged()
        val cancelledAt = System.nanoTime()
        job.cancel()
        job.join()
        val latencyMillis = (System.nanoTime() - cancelledAt) / 1_000_000
        instrumentation.sendStatus(0, Bundle().apply { putString("cancelLatencyMillisWhileProviderStalled", latencyMillis.toString()) })
        assertTrue(job.isCancelled)
        assertEquals("a stage must be active while the provider is stalled", 1, stagedWhenCancelled.size)
        assertNoSuccessFor(results, "second.jpg", "third.jpg")
        assertEquals(listOf("first.jpg"), names())
        delay(500)
        assertEquals(listOf("first.jpg"), names())
        assertFalse(names().any { it.endsWith(".part") })
    }

    private fun assertNoSuccessFor(results: List<ExtractResult>, vararg names: String) {
        for (name in names) {
            assertTrue("$name must not be reported as written", results.none { it.fileName == name && it.error == null })
        }
    }

    private companion object {
        const val LARGE = "large.zip"
        const val TRUNCATED = "truncated.zip"
        const val FAULTY = "faulty.zip"
        const val STALLED = "stalled.zip"
        const val FIRST_SIZE = 4096
        const val SECOND_SIZE = 1024 * 1024
        const val CANCEL_AFTER_BYTES = 60_000L
    }
}
