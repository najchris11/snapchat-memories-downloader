package com.najdev.snapvault.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Round 1 fixed two light-theme bugs caused by hardcoded colours, and round 2 replaced the
 * remaining fifteen with named [MediaColors] tokens. The value of that is entirely in it
 * staying true: a bare `Color.Black` in a UI file used to be ambiguous — deliberate media
 * colour, or forgotten theme colour? — and the naming only removes that ambiguity if new
 * ones cannot appear.
 *
 * Detekt has no rule for this, so it is asserted here. Colour belongs to the theme package;
 * screens reach it through colorScheme, SnapVaultColors, LogColors or MediaColors.
 */
class RawColorUsageTest {

    @Test
    fun uiCodeDeclaresNoRawColours() {
        val offenders = uiSourceFiles().flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                val code = line.substringBefore("//")
                RAW_COLOR.containsMatchIn(code)
            }.map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }

        assertTrue(
            offenders.isEmpty(),
            "Raw colours in UI code — use colorScheme, SnapVaultColors, LogColors or " +
                "MediaColors instead:\n" + offenders.joinToString("\n").prependIndent("  "),
        )
    }

    private fun uiSourceFiles(): List<File> {
        val roots = listOf("commonMain", "desktopMain", "androidMain", "iosMain")
            .map { File("src/$it/kotlin/com/najdev/snapvault/ui") }
            .filter { it.isDirectory }
        check(roots.isNotEmpty()) { "no UI source roots found from ${File(".").absolutePath}" }
        return roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }
        // The theme package is where colour is allowed to be declared.
        }.filterNot { it.parentFile.name == "theme" }
    }

    private companion object {
        // Color.Black / Color.White / Color(0xFF…). Color.Transparent is not a colour so
        // much as an absence, and reads clearly wherever it appears.
        val RAW_COLOR = Regex("""Color\.(Black|White|Red|Green|Blue|Gray|LightGray|DarkGray|Yellow|Cyan|Magenta)\b|Color\(0x[0-9A-Fa-f]{6,8}\)""")
    }
}
