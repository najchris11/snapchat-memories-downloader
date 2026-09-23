package com.najdev.snapvault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every external link in the iOS app was dead: the onboarding walkthrough's "request your
 * data" and video buttons, and the Help button in the top bar. Tapping one did nothing and
 * reported nothing, because `openUrl` called `UIApplication.openURL:` — the one-argument
 * form, deprecated `ios(2.0, 10.0)`, which a current iOS no longer services.
 *
 * Nothing caught it, and nothing running could have. The binding is still in UIKit, so it
 * compiles; it fails at runtime by silently doing nothing, and `iosMain` has no test source
 * set to run a test in even if there were an observable effect. This reads the source
 * instead, the way [com.najdev.snapvault.ui.StringResourceHygieneTest] does for strings —
 * the defect is a call that is *absent*, which is not a shape a normal test can assert on.
 */
class IosUrlOpeningTest {

    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
    }

    private val iosSources: List<File>
        get() = File(repoRoot, "composeApp/src/iosMain")
            .walkTopDown().filter { it.extension == "kt" }.toList()

    private val iosPlatform: File
        get() = File(repoRoot, "composeApp/src/iosMain/kotlin/com/najdev/snapvault/Platform.kt")

    /** The text between the braces of the first declaration whose line contains [signature]. */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "no declaration matching \"$signature\" — this test is stale")
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, i)
            }
        }
        error("unbalanced braces after \"$signature\"")
    }

    @Test
    fun theIosUrlOpenerCallsTheApiThatStillOpensUrls() {
        val body = bodyOf(iosPlatform.readText(), "actual fun openUrl(")

        assertTrue(
            "openURL(" in body,
            "openUrl no longer hands the URL to UIApplication at all:\n$body",
        )
        assertTrue(
            "completionHandler" in body,
            "openUrl calls the deprecated one-argument openURL:, which compiles and then " +
                "opens nothing on a current iOS. Use openURL:options:completionHandler:.\n$body",
        )
    }

    /**
     * The same call is a mistake anywhere in iosMain, not only in `openUrl`. Matches
     * `openURL(x)` with a single argument and no trailing comma — the deprecated shape.
     */
    @Test
    fun noIosCodeCallsTheDeprecatedSingleArgumentOpenUrl() {
        val deprecatedForm = Regex("""\.openURL\([^,()]*\)""")
        val offenders = iosSources.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> deprecatedForm.containsMatchIn(line) }
                .map { (index, line) -> "${file.name}:${index + 1}  ${line.trim()}" }
        }

        assertTrue(
            offenders.isEmpty(),
            "deprecated one-argument openURL: in iOS code — it compiles and does nothing:\n" +
                offenders.joinToString("\n"),
        )
    }
}
