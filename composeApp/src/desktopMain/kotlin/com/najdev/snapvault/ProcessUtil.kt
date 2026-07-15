package com.najdev.snapvault

// Waits for the process, killing it if the waiting thread is interrupted.
// Coroutine cancellation reaches blocking code as a thread interrupt (via
// runInterruptible / runInterruptibleCompat); without this, cancelling the
// pipeline would leave ffmpeg/exiftool children running to completion.
internal fun Process.waitForOrKill(): Int = try {
    waitFor()
} catch (e: InterruptedException) {
    destroyForcibly()
    throw e
}
