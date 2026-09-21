package com.najdev.snapvault

import com.najdev.snapvault.model.FileMeta
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Source
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Windows opening the index while [VaultIndex.writeAtomically] is mid-replace throws
 * `FileNotFoundException` with "the process cannot access the file because it is being used by
 * another process" — the same exception type Java gives for a file that is genuinely absent.
 * [VaultIndex.read] used to treat both as absence, so a Library scan or a favorite toggle could
 * observe a perfectly healthy index as empty (`VaultIndexConcurrencyTest`, and
 * docs/audits/windows-index-swap-2026-09-19.md). This only ever showed up on the Windows release
 * build: POSIX `rename(2)` does not create this window.
 *
 * These tests drive the failure from the read side so it is provable on any platform, mirroring
 * [VaultIndexAtomicMoveRetryTest] for the write side.
 */
class VaultIndexReadRetryTest {

    private lateinit var dir: File
    private lateinit var folder: String

    @BeforeTest
    fun setUp() {
        dir = File.createTempFile("vault-index-read-retry", "").apply { delete(); mkdirs() }
        folder = dir.absolutePath
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** Fails the first [failures] opens of the index the way Windows does, then behaves. */
    private class FlakyOpen(
        delegate: FileSystem,
        private val failures: Int,
    ) : ForwardingFileSystem(delegate) {
        var attempts = 0
            private set

        override fun source(file: Path): Source {
            if (file.name == VaultIndex.FILE_NAME) {
                attempts++
                if (attempts <= failures) {
                    throw FileNotFoundException(
                        "$file (The process cannot access the file because it is being used by another process)",
                    )
                }
            }
            return super.source(file)
        }
    }

    private val meta = mapOf("memory.jpg" to FileMeta(hasGps = true, hasOverlay = false, favorited = true))

    // The regression: one transient refusal used to read back as an empty index.
    @Test
    fun aReadSurvivesATransientRefusalToOpenTheIndex() = runBlocking {
        VaultIndex.write(FileSystem.SYSTEM, folder, meta)
        val fs = FlakyOpen(FileSystem.SYSTEM, failures = 1)

        assertEquals(meta, VaultIndex.read(fs, folder))
        assert(fs.attempts > 1) { "the open should have been retried, was attempted ${fs.attempts}x" }
    }

    // A writer can hold the replace across more than one attempt; the retry has to outlast that.
    @Test
    fun aReadSurvivesSeveralConsecutiveRefusals() = runBlocking {
        VaultIndex.write(FileSystem.SYSTEM, folder, meta)
        val fs = FlakyOpen(FileSystem.SYSTEM, failures = 3)

        assertEquals(meta, VaultIndex.read(fs, folder))
    }

    // The same exception type is what a genuinely missing index throws — this must not become
    // "retry forever" or turn a normal first run into a slow one.
    @Test
    fun aMissingIndexReadsAsEmptyWithoutRetrying() = runBlocking {
        val fs = FlakyOpen(FileSystem.SYSTEM, failures = Int.MAX_VALUE)

        assertEquals(emptyMap(), VaultIndex.read(fs, folder))
        assert(fs.attempts == 1) { "a genuinely missing index should not be retried, attempted ${fs.attempts}x" }
    }

    // A permanent refusal on a file that does exist must still resolve — read() stays total.
    @Test
    fun aPermanentRefusalOnAnExistingIndexStillReadsAsEmpty() = runBlocking {
        VaultIndex.write(FileSystem.SYSTEM, folder, meta)
        val fs = FlakyOpen(FileSystem.SYSTEM, failures = Int.MAX_VALUE)

        assertEquals(emptyMap(), VaultIndex.read(fs, folder))
    }
}
