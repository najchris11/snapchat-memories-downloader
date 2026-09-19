package com.najdev.snapvault

import java.io.BufferedReader
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class FileOutputDirectoryLockerTest {

    private val locker = FileOutputDirectoryLocker

    private fun tempDir(name: String): File =
        createTempDirectory(name).toFile().also { it.deleteOnExit() }

    private fun noWarnings(): (String) -> Unit = { fail("unexpected warning: $it") }

    /**
     * Runs [LockHolder]'s main in a real second JVM and returns once it reports the claim.
     *
     * Reusing this JVM's own java binary and classpath keeps the child identical to the
     * parent without any build wiring; the caller destroys the process to release the lock.
     */
    private fun holdInAnotherProcess(folder: File): Process {
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        val process = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            "com.najdev.snapvault.LockHolderKt",
            folder.absolutePath,
        ).redirectErrorStream(false).start()

        val ready = process.inputStream.bufferedReader().awaitLine(HOLDER_READY)
        if (!ready) {
            val stderr = process.errorStream.bufferedReader().readText()
            process.destroyForcibly()
            fail("lock holder process never reported $HOLDER_READY; stderr was:\n$stderr")
        }
        return process
    }

    private fun BufferedReader.awaitLine(expected: String): Boolean {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val line = readLine() ?: return false
            if (line.trim() == expected) return true
        }
        return false
    }

    private fun Process.stopAndWait() {
        destroy()
        if (!waitFor(10, TimeUnit.SECONDS)) destroyForcibly()
    }

    // The finding itself (D07). Every other guard in the app is process-local, so this is the
    // only assertion that speaks to the actual failure: a genuinely separate JVM holding the
    // directory must make our claim fail, not merely be noticed.
    @Test
    fun aDirectoryHeldByAnotherProcessIsRefused() {
        val folder = tempDir("snapvault-lock-crossprocess")
        val holder = holdInAnotherProcess(folder)
        try {
            val refusal = assertFailsWith<OutputDirectoryInUseException> {
                locker.lock(folder.absolutePath, noWarnings())
            }
            assertEquals(folder.absolutePath, refusal.folder)
            assertTrue(
                refusal.message!!.contains("already being updated"),
                "the refusal has to read as an explanation, was: ${refusal.message}",
            )
        } finally {
            holder.stopAndWait()
        }
    }

    // A lock nobody releases is a library nobody can sync again. The holder exiting must hand
    // the directory back — including when it exits without a clean release, which for a
    // desktop app closed with the red button is the ordinary case, not the exotic one.
    @Test
    fun theDirectoryIsClaimableAgainOnceTheOtherProcessExits() {
        val folder = tempDir("snapvault-lock-handback")
        holdInAnotherProcess(folder).stopAndWait()

        // The OS drops a file lock when the process holding it dies, however it died.
        locker.lock(folder.absolutePath, noWarnings()).release()
    }

    @Test
    fun releasingALockLetsTheSameProcessClaimItAgain() {
        val folder = tempDir("snapvault-lock-reacquire")
        locker.lock(folder.absolutePath, noWarnings()).release()
        locker.lock(folder.absolutePath, noWarnings()).release()
    }

    // Two runs inside one JVM are refused by the same rule as two processes. Not the finding,
    // but the contract has to be one rule: a caller that gets a lock back has exclusive use of
    // the directory, and it must not matter who the other claimant happens to be.
    @Test
    fun aDirectoryThisProcessAlreadyHoldsIsRefusedToo() {
        val folder = tempDir("snapvault-lock-samejvm")
        val first = locker.lock(folder.absolutePath, noWarnings())
        try {
            assertFailsWith<OutputDirectoryInUseException> {
                locker.lock(folder.absolutePath, noWarnings())
            }
        } finally {
            first.release()
        }
    }

    // A lock that is too broad is its own bug: two libraries in two folders are exactly the
    // case a user with an archive and a working copy has, and they do not conflict.
    @Test
    fun separateDirectoriesDoNotBlockEachOther() {
        val a = tempDir("snapvault-lock-a")
        val b = tempDir("snapvault-lock-b")
        val lockA = locker.lock(a.absolutePath, noWarnings())
        val lockB = locker.lock(b.absolutePath, noWarnings())
        lockA.release()
        lockB.release()
    }

    // Exclusion is by directory *identity*, not by the path string the user happened to pick.
    // A `..` hop and a symlink are the two ways one library gets two names — a folder picker
    // and a drag-and-drop of the same directory can genuinely disagree about its spelling —
    // and both must still be one lock.
    //
    // This holds because the kernel arbitrates against the sentinel's inode, which every
    // spelling opens. What it guards against is somebody replacing that with a registry keyed
    // on path strings, which would look equivalent and quietly let both runs through.
    @Test
    fun oneDirectoryReachedTwoWaysIsStillOneLock() {
        val folder = tempDir("snapvault-lock-spelling")
        val viaParentHop = File(folder, "..").resolve(folder.name).path
        val viaSymlink = Files.createSymbolicLink(
            File(tempDir("snapvault-lock-symlink"), "link").toPath(),
            folder.toPath(),
        ).toFile().path

        val first = locker.lock(folder.absolutePath, noWarnings())
        try {
            assertFailsWith<OutputDirectoryInUseException> { locker.lock(viaParentHop, noWarnings()) }
            assertFailsWith<OutputDirectoryInUseException> { locker.lock(viaSymlink, noWarnings()) }
        } finally {
            first.release()
        }
    }

    // Not being able to ask is not the same answer as "no". A directory we cannot write a
    // sentinel into gives no evidence of a second writer, and refusing the run there would
    // strand a user over a filesystem quirk — so it warns and proceeds.
    @Test
    fun anUnlockableLocationWarnsAndLetsTheRunProceed() {
        // A path whose parent is a regular file can never hold a sentinel, on any platform,
        // without depending on permissions the test runner may or may not have.
        val file = File(tempDir("snapvault-lock-notadir"), "regular-file").apply { writeText("x") }
        val warnings = mutableListOf<String>()

        val lock = locker.lock(File(file, "library").path) { warnings.add(it) }

        assertEquals(UnenforcedOutputDirectoryLock, lock)
        assertTrue(warnings.isNotEmpty(), "proceeding without a lock has to be said out loud")
        lock.release()
    }

    // The sentinel deliberately outlives the lock — deleting it on release would let a second
    // process that had already opened it end up locking an unlinked inode while a third
    // created a fresh file, and two "holders" of different files is the unguarded case again.
    // What it must not do is turn up in the user's library as something to wonder about.
    @Test
    fun theSentinelStaysBehindButIsHiddenAndEmpty() {
        val folder = tempDir("snapvault-lock-sentinel")
        val existing = folder.listFiles().orEmpty().toSet()

        locker.lock(folder.absolutePath, noWarnings()).release()

        val added = folder.listFiles().orEmpty().filterNot { it in existing }
        assertEquals(1, added.size, "expected exactly one sentinel, got: ${added.map { it.name }}")
        assertTrue(added.single().name.startsWith("."), "the sentinel must be hidden: ${added.single().name}")
        assertEquals(0L, added.single().length(), "the sentinel carries no data, only the lock")
    }
}
