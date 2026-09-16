package com.najdev.snapvault

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Claims an output directory with an exclusive OS lock on a hidden sentinel file inside it.
 *
 * The directory is the only thing two SnapVault processes both know about, so it is the only
 * place the claim can live (D07). An OS-level [FileLock] is what makes this cross-process:
 * the kernel arbitrates it, and — the part that matters for a desktop app people close with
 * the red button — it drops the lock when the holding process dies, however it died. Nothing
 * here has to be cleaned up after a crash.
 *
 * The sentinel file itself is *not* deleted on release. Unlinking it would open the very race
 * it exists to close: a second process that had already opened the file would go on to lock an
 * inode with no name, while a third created a fresh file and locked that, leaving two holders
 * of two different files and no exclusion at all. An empty hidden file is the cheaper problem.
 */
object FileOutputDirectoryLocker : OutputDirectoryLocker {

    internal const val SENTINEL_NAME = ".snapvault-lock"

    override fun lock(folder: String, onWarn: (String) -> Unit): OutputDirectoryLock {
        // Absolute only. Resolving symlinks and `..` here would be reassuring and pointless:
        // exclusion is decided by the kernel against the sentinel's inode, which every
        // spelling of the path opens anyway. Normalising the string is what a lock keyed on
        // paths would need, and keying on paths is the thing to avoid.
        val dir = File(folder).absoluteFile
        val sentinel = File(dir, SENTINEL_NAME)

        val handle = try {
            dir.mkdirs()
            RandomAccessFile(sentinel, "rw")
        } catch (e: IOException) {
            return unenforced(folder, e, onWarn)
        } catch (e: SecurityException) {
            return unenforced(folder, e, onWarn)
        }

        val lock: FileLock? = try {
            handle.channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            // This JVM already holds it. Different cause, identical consequence: somebody else
            // is writing this library, and the caller must not.
            handle.close()
            throw OutputDirectoryInUseException(folder)
        } catch (e: IOException) {
            handle.close()
            return unenforced(folder, e, onWarn)
        }

        if (lock == null) {
            handle.close()
            throw OutputDirectoryInUseException(folder)
        }
        return Held(lock, handle)
    }

    // Being unable to ask is not the same answer as "no" — see UnenforcedOutputDirectoryLock.
    // Network shares and filesystems without lock support land here, and a user whose library
    // lives on one still gets to run SnapVault; they just do not get the guard.
    private fun unenforced(
        folder: String,
        cause: Exception,
        onWarn: (String) -> Unit,
    ): OutputDirectoryLock {
        onWarn(
            "could not claim $folder exclusively (${cause.message}) — continuing without the " +
                "guard that stops a second SnapVault window writing here at the same time",
        )
        return UnenforcedOutputDirectoryLock
    }

    private class Held(
        private val lock: FileLock,
        private val handle: RandomAccessFile,
    ) : OutputDirectoryLock {
        override fun release() {
            // Neither failure is actionable and both are moot once the handle is closed, which
            // releases the lock regardless — a throw here would only mask the run's own result.
            runCatching { lock.release() }
            runCatching { handle.close() }
        }
    }
}

actual val platformOutputDirectoryLocker: OutputDirectoryLocker = FileOutputDirectoryLocker
