package com.najdev.snapvault

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tracked commit-msg hook, run the way git runs it.
 *
 * The hook exists because session tooling repeatedly tried to append a Co-Authored-By trailer
 * crediting Claude, against both this repo's rule and a standing preference. A hook nobody
 * tests is a hook that silently stops matching — and the failure is invisible, because a hook
 * that matches nothing looks exactly like a repo with nothing to catch.
 *
 * The other half matters as much: two real commits in this history discuss CLAUDE.md in their
 * bodies, and a hook that rejected those would be worse than no hook at all.
 */
class CommitMsgHookTest {

    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
    }

    private val hook: File get() = File(repoRoot, ".githooks/commit-msg")

    // Windows release runners have sh through Git for Windows, but do not execute a
    // shebang natively. Invoking the interpreter directly works on every platform.
    private fun shAvailable(): Boolean = runCatching {
        ProcessBuilder("sh", "-c", "exit 0").start().waitFor() == 0
    }.getOrDefault(false)

    /** True when the hook accepts [message]. */
    private fun accepts(message: String): Boolean {
        val dir = createTempDirectory("hook").toFile()
        return try {
            val msgFile = File(dir, "COMMIT_EDITMSG").apply { writeText(message) }
            val process = ProcessBuilder("sh", hook.absolutePath, msgFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            process.inputStream.readBytes()
            process.waitFor() == 0
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theHookIsTrackedAndExecutable() {
        assertTrue(hook.isFile, "the hook must be committed at .githooks/commit-msg, not left in .git/hooks")
        // git ignores a hook without the executable bit, and does so silently.
        assertTrue(hook.canExecute(), "the hook must be executable or git will skip it without saying so")
    }

    @Test
    fun everyFormOfAiAttributionIsRejected() {
        if (!shAvailable()) return
        val rejected = mapOf(
            "Co-Authored-By trailer" to "fix: thing\n\nCo-Authored-By: Claude Opus 5 <noreply@anthropic.com>",
            "lowercase trailer" to "fix: thing\n\nco-authored-by: claude <noreply@anthropic.com>",
            "Claude-Session trailer" to "fix: thing\n\nClaude-Session: https://claude.ai/code/session_01Sx",
            "Generated with footer" to "fix: thing\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)",
            "bare robot emoji" to "fix: thing 🤖",
        )
        for ((name, message) in rejected) {
            assertEquals(false, accepts(message), "$name must be rejected")
        }
    }

    // The half that is easy to get wrong. A hook matching "claude" anywhere would reject
    // both of these, and they are real commits in this repository (c57a8eb, a0ea26d).
    @Test
    fun ordinaryMessagesAboutTheAgentInstructionsAreAccepted() {
        if (!shAvailable()) return
        val accepted = mapOf(
            "prose naming CLAUDE.md" to
                "chore: correct the agent instructions\n\nCLAUDE.md and AGENTS.md both still claimed there was no detekt.",
            "prose adding CLAUDE.md" to
                "UI audit round 1 (#31)\n\nAdds CLAUDE.md and AGENTS.md: testing policy first, then theming rules,",
            "a human co-author" to
                "fix: thing\n\nCo-Authored-By: Manuel Puchner <manuelpuchner@icloud.com>",
            "an ordinary commit" to
                "feat(android): make the ZIP pipeline actually run on Android",
            "a commented-out trailer from git's template" to
                "fix: thing\n# Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>",
        )
        for ((name, message) in accepted) {
            assertEquals(true, accepts(message), "$name must be accepted")
        }
    }

    // A human co-author is the case most at risk from a lazy broadening of the pattern —
    // this project has real ones (Manuel Puchner, Nick) in its history.
    @Test
    fun aHumanCoAuthorIsNeverCollateralDamage() {
        if (!shAvailable()) return
        assertTrue(
            accepts("fix: thing\n\nCo-Authored-By: Nick <nicholas.r.cupo@gmail.com>"),
            "crediting a person must always be allowed",
        )
    }
}
