package com.najdev.snapvault.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Strings drifted out of `strings.xml` in both directions and nothing noticed either way:
 * 35 user-facing literals accumulated in UI files, while twelve resources written for
 * features that changed or never shipped sat unreferenced.
 *
 * Neither is a defect a normal test can catch, because both are about code that is *absent*.
 * These read the sources instead. They are cheap, they run with the suite rather than needing
 * CI wiring of their own, and they fail with the exact key or line at fault.
 */
class StringResourceHygieneTest {

    // Text that is deliberately not translated, with the reason. Anything not on this list
    // has to become a resource — which is the point: a new literal forces a decision rather
    // than sliding in.
    private val notCopy = mapOf(
        "$ " to "shell prompt on the log panel's terminal surface",
        "running_pipeline" to "monospace log content, not prose",
    )

    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
    }

    private val stringsXml: File
        get() = File(repoRoot, "composeApp/src/commonMain/composeResources/values/strings.xml")

    private val uiSources: List<File>
        get() = File(repoRoot, "composeApp/src/commonMain/kotlin/com/najdev/snapvault/ui")
            .walkTopDown().filter { it.extension == "kt" }.toList()

    private val kotlinSources: List<File>
        get() = File(repoRoot, "composeApp/src").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun everyStringResourceIsReferenced() {
        val declared = Regex("""<(string|plurals) name="([^"]+)"""")
            .findAll(stringsXml.readText())
            .map { it.groupValues[2] }
            .toSet()
        assertTrue(declared.size > 50, "parsed only ${declared.size} keys — the regex is wrong, not the file")

        val sources = kotlinSources.joinToString("\n") { it.readText() }
        val unused = declared.filterNot { key ->
            sources.contains("Res.string.$key") || sources.contains("Res.plurals.$key")
        }

        assertEquals(
            emptyList(), unused.sorted(),
            "unreferenced string resources — delete them, or wire up whatever was meant to use them",
        )
    }

    // Both the visible-text form and the two ways a contentDescription gets written. The
    // second matters as much: an unextracted contentDescription is text a screen reader
    // speaks, in whatever language the source happened to be written in.
    private val displayTextForms = listOf(
        Regex("""Text\(\s*"((?:[^"\\]|\\.)*)""""),
        Regex("""contentDescription\s*=\s*"((?:[^"\\]|\\.)*)""""),
        Regex("""Icon\([^,\n]+,\s*"((?:[^"\\]|\\.)*)""""),
    )

    @Test
    fun uiCodeHasNoHardcodedDisplayText() {
        val offenders = uiSources.flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                displayTextForms.flatMap { form -> form.findAll(line).map { it.groupValues[1] } }
                    .filterNot { it in notCopy }
                    .map { "${file.name}:${index + 1}  \"$it\"" }
            }
        }

        assertEquals(
            emptyList(), offenders.sorted(),
            "hardcoded display text in UI code — move it to strings.xml, or add it to " +
                "`notCopy` with the reason it is not translatable prose",
        )
    }
}
