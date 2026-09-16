package com.najdev.snapvault

import java.util.concurrent.TimeUnit

/** What a child process said and how it ended. */
internal data class CommandResult(val exitCode: Int, val output: String)

/** A child that outlived its timeout and was killed rather than waited on any longer. */
internal class CommandTimeoutException(command: String, timeoutMillis: Long) :
    Exception("$command did not finish within ${timeoutMillis}ms and was terminated")

/**
 * Long enough that no exiftool or ffmpeg invocation SnapVault makes should ever reach it, and
 * short enough that a wedged one does not hold a pipeline phase open for the rest of the day.
 * It is a backstop, not a schedule: Stop cancels, and cancellation does not wait for this.
 */
internal const val DEFAULT_COMMAND_TIMEOUT_MS = 10 * 60 * 1000L

// How long a child gets to die politely before it is killed, and how long the kill itself is
// given to take effect. Java documents that forcible destruction may not complete immediately.
private const val TERMINATION_GRACE_MS = 2_000L

// How long the drain thread gets to deliver the tail of the output after the child has exited.
private const val DRAIN_GRACE_MS = 2_000L

/**
 * Waits for the process, killing it if this thread is interrupted, and confirming it died.
 *
 * Coroutine cancellation reaches blocking code as a thread interrupt (via `runInterruptible` /
 * [runInterruptibleCompat]). Without this, cancelling the pipeline left ffmpeg and exiftool
 * children running to completion — Stop said "Stopping…" while the child kept writing to the
 * user's files.
 *
 * Confirming matters as much as signalling. `destroy()` is a request a child can sit on
 * indefinitely inside a shutdown hook, and even `destroyForcibly()` is documented as possibly
 * not completing immediately, so returning right after asking would be the same bug wearing a
 * kill call (D06).
 */
internal fun Process.awaitOrKill(timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS): Int {
    val finished = try {
        waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        terminateAndConfirm()
        throw e
    }
    if (!finished) {
        terminateAndConfirm()
        throw CommandTimeoutException(info().command().orElse("child process"), timeoutMillis)
    }
    return exitValue()
}

/**
 * [awaitOrKill] with no timeout, for a child whose duration is the work itself.
 *
 * An ffmpeg encode of a long video legitimately runs for minutes, and any fixed limit would
 * eventually kill a healthy one. It reads no output (callers discard both streams), so there
 * is no blocking read to escape: the interrupt reaches `waitFor` directly, and the kill is
 * still confirmed.
 */
internal fun Process.waitForOrKill(): Int = try {
    waitFor()
} catch (e: InterruptedException) {
    terminateAndConfirm()
    throw e
}

/**
 * Runs [args] to completion and returns its merged output and exit code.
 *
 * Two things here are not incidental. The streams are merged, because a child that fills the
 * pipe nobody is reading blocks forever while the parent blocks on `waitFor` — the deadlock
 * every call site in this codebase used to guard against by hand. And the draining happens on
 * its own thread, because a blocking read on a process pipe does **not** answer to
 * `Thread.interrupt()`: reading on the calling thread, as the old call sites did, meant
 * cancellation could not reach them at all until the child chose to speak (D06).
 *
 * @throws CommandTimeoutException if the child outlives [timeoutMillis].
 * @throws InterruptedException if this thread is interrupted; the child is killed first.
 */
internal fun runCommand(
    args: List<String>,
    timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
): CommandResult {
    val process = ProcessBuilder(args).redirectErrorStream(true).start()
    // A child that reads stdin gets EOF rather than waiting on input that will never come.
    runCatching { process.outputStream.close() }

    val collected = StringBuilder()
    // Daemon: a child that never closes its output must not be able to keep the JVM alive
    // after we have given up on it.
    val drain = Thread {
        runCatching {
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    synchronized(collected) { collected.appendRange(buffer, 0, read) }
                }
            }
        }
    }
    drain.isDaemon = true
    drain.name = "snapvault-command-drain"
    drain.start()

    val exitCode = process.awaitOrKill(timeoutMillis)
    // The child has exited; the tail of its output may still be in flight. Bounded, because a
    // wedged drain must not become the hang this function exists to remove.
    drain.join(DRAIN_GRACE_MS)
    return CommandResult(exitCode, synchronized(collected) { collected.toString() })
}

/**
 * Ends the process and reports whether it is actually gone.
 *
 * Asks first, then insists. Both waits tolerate further interruption without abandoning the
 * child — this usually runs while already unwinding one interrupt, and a second must not be
 * what leaves an ffmpeg behind.
 */
private fun Process.terminateAndConfirm(): Boolean {
    destroy()
    if (waitUninterruptibly(TERMINATION_GRACE_MS)) return true
    destroyForcibly()
    return waitUninterruptibly(TERMINATION_GRACE_MS)
}

private fun Process.waitUninterruptibly(millis: Long): Boolean {
    val deadline = System.nanoTime() + millis * 1_000_000
    var interrupted = false
    try {
        while (true) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) return !isAlive
            try {
                return waitFor(remainingMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    } finally {
        // Swallowing the interrupt would strand the cancellation this is unwinding.
        if (interrupted) Thread.currentThread().interrupt()
    }
}
