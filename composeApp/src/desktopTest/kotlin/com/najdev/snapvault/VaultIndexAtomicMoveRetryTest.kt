package com.najdev.snapvault

import com.najdev.snapvault.model.FileMeta
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Windows refuses to replace a file another handle has open, so [VaultIndex.writeAtomically]'s
 * `atomicMove` throws `AccessDeniedException` whenever a reader happens to hold the index —
 * and [VaultIndex.read] deliberately holds no lock, so that is an ordinary interleaving, not a
 * rare one. In the app it meant favoriting a photo while the Library was scanning could fail.
 * POSIX `rename(2)` does not care about open handles, which is why this only ever showed up on
 * the Windows release build and never on macOS or Linux.
 *
 * The reader's handle is gone in microseconds, so the fix is to retry the move rather than to
 * fall back to a non-atomic copy — a copy would reintroduce exactly the torn read the temp
 * file exists to prevent. These tests drive the retry from the failure side so the behaviour
 * is provable on any platform.
 */
class VaultIndexAtomicMoveRetryTest {

    private lateinit var dir: File
    private lateinit var folder: String

    @BeforeTest
    fun setUp() {
        dir = File.createTempFile("vault-index-retry", "").apply { delete(); mkdirs() }
        folder = dir.absolutePath
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Fails the first [failures] `atomicMove` calls the way Windows does, then behaves. */
    private class FlakyMove(
        delegate: FileSystem,
        private val failures: Int,
    ) : ForwardingFileSystem(delegate) {
        var attempts = 0
            private set

        override fun atomicMove(source: Path, target: Path) {
            attempts++
            if (attempts <= failures) throw IOException("access is denied (simulated Windows)")
            super.atomicMove(source, target)
        }
    }

    private val meta = mapOf("memory.jpg" to FileMeta(hasGps = true, hasOverlay = false, favorited = true))

    // The regression: one transient refusal used to fail the whole write, losing the favorite.
    @Test
    fun aWriteSurvivesATransientRefusalToReplaceTheIndex() = runBlocking {
        val fs = FlakyMove(FileSystem.SYSTEM, failures = 1)

        VaultIndex.write(fs, folder, meta)

        assertEquals(meta, VaultIndex.read(FileSystem.SYSTEM, folder))
        assertTrue(fs.attempts > 1, "the move should have been retried, was attempted ${fs.attempts}x")
    }

    // A reader can hold the file across more than one attempt; the retry has to outlast that.
    @Test
    fun aWriteSurvivesSeveralConsecutiveRefusals() = runBlocking {
        val fs = FlakyMove(FileSystem.SYSTEM, failures = 3)

        VaultIndex.write(fs, folder, meta)

        assertEquals(meta, VaultIndex.read(FileSystem.SYSTEM, folder))
    }

    // Retrying must not become "retry forever": a genuine permission problem still has to
    // surface, and it must not leave the temp file for the next write to trip over.
    @Test
    fun aPermanentRefusalStillFailsAndLeavesNoTemporaryFileBehind() = runBlocking {
        val fs = FlakyMove(FileSystem.SYSTEM, failures = Int.MAX_VALUE)

        assertFailsWith<IOException> { VaultIndex.write(fs, folder, meta) }

        assertEquals(
            emptyList(),
            dir.list()!!.sorted(),
            "a failed write must clean up after itself, found: ${dir.list()!!.toList()}",
        )
    }

    // The favorite being written must survive the retry, not just the file appearing.
    @Test
    fun theFavoriteSurvivesARetriedWrite() = runBlocking {
        val fs = FlakyMove(FileSystem.SYSTEM, failures = 2)

        VaultIndex.setFavorite(fs, folder, "memory.jpg", true)

        assertEquals(true, VaultIndex.read(FileSystem.SYSTEM, folder)["memory.jpg"]?.favorited)
    }
}
