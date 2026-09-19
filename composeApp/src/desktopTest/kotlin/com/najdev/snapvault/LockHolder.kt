package com.najdev.snapvault

/**
 * A second process that holds an output directory, for [FileOutputDirectoryLockerTest].
 *
 * The whole point of D07 is that SnapVault's guards are process-local, so a test that only
 * takes the lock twice inside one JVM proves nothing about the case the fix is for. This is
 * launched as a real second JVM: it claims the directory named by `args[0]`, prints a line so
 * the parent knows the claim has landed, and then holds it until its stdin closes.
 */
fun main(args: Array<String>) {
    val folder = args.first()
    val lock = FileOutputDirectoryLocker.lock(folder) { System.err.println("warn: $it") }
    println(HOLDER_READY)
    System.out.flush()
    // Blocks until the parent closes our stdin (or kills us), which is the test's signal
    // that it is done with the lock.
    generateSequence(::readLine).forEach { }
    lock.release()
}

const val HOLDER_READY = "LOCK-HELD"
