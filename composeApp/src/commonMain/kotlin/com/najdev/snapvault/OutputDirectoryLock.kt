package com.najdev.snapvault

/**
 * Another SnapVault is already writing this output directory.
 *
 * Every guard SnapVault has against concurrent writers — the extractor's move lock, the
 * FFmpeg semaphore, [VaultIndex]'s mutex — is process-local, so two windows open on the same
 * library agree about nothing. They clear each other's staging files, race onto the same
 * `.part` and `vault_index.json.tmp` names, and lose each other's favorites. The only place
 * that can be arbitrated is the directory itself (D07).
 */
class OutputDirectoryInUseException(val folder: String) : Exception(
    "This library is already being updated by another SnapVault window. Close it and try again.",
)

/** An acquired claim on an output directory, held for a run and released when it ends. */
interface OutputDirectoryLock {
    fun release()
}

/**
 * A claim that was never actually taken.
 *
 * Returned when locking could not be *attempted* — a filesystem with no lock support, a
 * directory we cannot write a sentinel into. Being unable to check is not evidence of a
 * conflict, and refusing the run would strand a user whose only fault is a network share, so
 * the run proceeds with a warning. A lock that was attempted and *denied* is the opposite
 * case and throws [OutputDirectoryInUseException].
 */
object UnenforcedOutputDirectoryLock : OutputDirectoryLock {
    override fun release() = Unit
}

fun interface OutputDirectoryLocker {
    /**
     * Claims [folder] for this process until the returned lock is released.
     *
     * @throws OutputDirectoryInUseException if another process holds it.
     */
    fun lock(folder: String, onWarn: (String) -> Unit): OutputDirectoryLock
}

/** Never refuses anything — for callers with no directory to protect, and for tests. */
object UnenforcedOutputDirectoryLocker : OutputDirectoryLocker {
    override fun lock(folder: String, onWarn: (String) -> Unit) = UnenforcedOutputDirectoryLock
}

expect val platformOutputDirectoryLocker: OutputDirectoryLocker
