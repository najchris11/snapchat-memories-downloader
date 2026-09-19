package com.najdev.snapvault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * No test may use the real preference store.
 *
 * `DashboardViewModel` remembers the library folder between sessions, and its default is the
 * machine's own preferences. A test taking that default reads whatever this machine happens to
 * hold — so it passes here and fails on another machine — and writes to it: a run of the suite
 * left `lastOutputFolder=/out` in the developer's real settings, and a later App-level test then
 * started with a folder already chosen.
 *
 * This reads the test sources, because the fault is a missing argument rather than anything a
 * running test can observe about itself.
 */
class TestPreferenceIsolationTest {

    @Test
    fun noTestBuildsAViewModelOrAppOnTheMachinesRealPreferences() {
        val repoRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val testSources = File(repoRoot, "composeApp/src/desktopTest")
            .walkTopDown().filter { it.extension == "kt" }.toList()
        require(testSources.size > 20) { "found only ${testSources.size} test files — wrong directory" }

        // Each construction call, with everything up to the balanced closing bracket.
        val offenders = testSources.flatMap { file ->
            val text = file.readText()
            Regex("""\b(DashboardViewModel|App)\(""").findAll(text).mapNotNull { match ->
                val call = balancedCall(text, match.range.last)
                if (call == null || "outputFolderMemory" in call) null else "${file.name}: ${match.groupValues[1]}("
            }
        }

        assertEquals(
            emptyList(), offenders.distinct().sorted(),
            "pass outputFolderMemory = OutputFolderMemory.None (or a fake) so the suite cannot read or write real settings",
        )
    }

    private fun balancedCall(text: String, openIndex: Int): String? {
        var depth = 0
        for (i in openIndex until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openIndex, i + 1)
                }
            }
        }
        return null
    }
}
