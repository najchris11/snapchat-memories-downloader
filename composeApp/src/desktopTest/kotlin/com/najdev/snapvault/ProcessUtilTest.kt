package com.najdev.snapvault

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ProcessUtilTest {

    private val started = mutableListOf<Process>()

    @AfterTest
    fun killAnySurvivors() {
        started.forEach {
            if (it.isAlive) {
                it.destroyForcibly()
                it.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun fixtureCommand(vararg args: String): List<String> = listOf(
        File(File(System.getProperty("java.home"), "bin"), "java").absolutePath,
        "-cp",
        System.getProperty("java.class.path"),
        "com.najdev.snapvault.ChildProcessFixtureKt",
    ) + args

    private fun startFixture(vararg args: String): Process =
        ProcessBuilder(fixtureCommand(*args)).redirectErrorStream(true).start().also { started += it }

    /**
     * Runs [block] on its own thread, interrupts it after [interruptAfterMs], and returns what
     * it threw. Fails the test if the call never comes back — which is the D06 hang itself.
     */
    private fun interruptedRun(interruptAfterMs: Long, block: () -> Unit): Throwable? {
        var thrown: Throwable? = null
        val worker = Thread { runCatching(block).onFailure { thrown = it } }
        worker.start()
        Thread.sleep(interruptAfterMs)
        worker.interrupt()
        worker.join(15_000)
        if (worker.isAlive) fail("the call never returned after the thread was interrupted — the D06 hang")
        return thrown
    }

    // ── awaitOrKill ─────────────────────────────────────────────────────────

    // D06: Stop must actually stop. The child holds its stdout pipe open and never exits, so
    // the only thing that can end the wait is the interrupt — and the only thing that can end
    // the *child* is us killing it. Leaving it running is how "Stopping…" stayed on screen
    // while ffmpeg and exiftool carried on writing to the user's files.
    @Test
    fun anInterruptedWaitKillsTheChildAndConfirmsItIsGone() {
        val child = startFixture("sleep")

        val thrown = interruptedRun(interruptAfterMs = 300) { child.awaitOrKill() }

        assertTrue(
            thrown is InterruptedException,
            "an interrupted wait has to propagate, was: $thrown",
        )
        assertFalse(child.isAlive, "the child must be dead before the call returns, not merely signalled")
    }

    // Java documents that forcible destruction may not complete immediately, and a child can
    // sit in a shutdown hook indefinitely after SIGTERM. Asking politely and walking away
    // leaves exactly the process the interrupt was supposed to stop.
    @Test
    fun aChildThatIgnoresTheGentleSignalIsStillForcedToDie() {
        val child = startFixture("ignore-term")
        // Wait for the shutdown hook to be installed, or destroy() would win by arriving first.
        assertEquals(READY, child.inputStream.bufferedReader().readLine())

        val thrown = interruptedRun(interruptAfterMs = 300) { child.awaitOrKill() }

        assertTrue(thrown is InterruptedException, "was: $thrown")
        assertFalse(child.isAlive, "a child that ignores SIGTERM must still be killed")
    }

    @Test
    fun aChildThatExitsOnItsOwnIsNotKilledAndReportsItsCode() {
        val child = startFixture("echo", "hello", "3")

        assertEquals(3, child.awaitOrKill())
    }

    // ── runCommand ──────────────────────────────────────────────────────────

    // The read, not the wait, was the part that could not be interrupted: every metadata and
    // image-fallback command blocked in readText() before it ever reached waitFor. A child
    // that never closes stdout therefore pinned the calling thread with no way out.
    @Test
    fun runCommandReturnsWhenTheCallerIsInterruptedEvenIfTheChildNeverSpeaks() {
        val thrown = interruptedRun(interruptAfterMs = 300) { runCommand(fixtureCommand("sleep")) }

        assertTrue(
            thrown is InterruptedException,
            "an interrupted command has to propagate, was: $thrown",
        )
    }

    // Nobody is always there to cancel. A wedged child with no timeout keeps a pipeline phase
    // open forever, and the run never reaches its terminal state.
    //
    // Run off the test thread and bounded, so that losing the timeout fails this test rather
    // than hanging the whole suite — which is the bug, but a poor way to report it.
    @Test
    fun aChildThatNeverExitsIsKilledWhenItsTimeoutPasses() {
        var thrown: Throwable? = null
        val worker = Thread {
            runCatching { runCommand(fixtureCommand("sleep"), timeoutMillis = 500) }.onFailure { thrown = it }
        }
        worker.start()
        worker.join(15_000)
        if (worker.isAlive) {
            worker.interrupt()
            fail("the timeout did not bound the call — a wedged child held it open")
        }

        assertTrue(thrown is CommandTimeoutException, "was: $thrown")
    }

    // Both pipes have to be drained while the child runs. Waiting first and reading after
    // deadlocks as soon as output exceeds the pipe buffer, which verbose exiftool reliably
    // does — the reason every call site already merges the streams by hand.
    @Test
    fun aChildThatFloodsBothStreamsIsDrainedRatherThanDeadlocked() {
        val result = runCommand(fixtureCommand("flood"), timeoutMillis = 30_000)

        assertEquals(0, result.exitCode)
        assertTrue(STDOUT_END in result.output, "stdout was not drained to the end")
        assertTrue(STDERR_END in result.output, "stderr was not drained to the end")
    }

    @Test
    fun anOrdinaryCommandReportsItsOutputAndExitCode() {
        val result = runCommand(fixtureCommand("echo", "hello", "3"))

        assertEquals(3, result.exitCode)
        assertEquals("hello", result.output.trim())
    }
}
