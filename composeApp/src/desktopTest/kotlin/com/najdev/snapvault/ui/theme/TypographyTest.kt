package com.najdev.snapvault.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The type scale replaced 87 hardcoded `fontSize` literals. Two things have to stay true for
 * that to keep being worth anything: the floor cannot be lowered by editing the theme, and
 * new literals cannot creep back into screens.
 */
class TypographyTest {

    // The old scale bottomed out at 8sp, with 9sp at six more sites and 10sp carrying
    // section labels and stepper labels. Anything below 11sp is not reliably legible, and
    // lowering a role in the theme is a one-character change nothing else would catch.
    @Test
    fun noRoleInTheScaleFallsBelowTheLegibleFloor() {
        val roles = mapOf(
            "headlineSmall" to SnapVaultTypography.headlineSmall,
            "titleLarge" to SnapVaultTypography.titleLarge,
            "titleMedium" to SnapVaultTypography.titleMedium,
            "titleSmall" to SnapVaultTypography.titleSmall,
            "bodyMedium" to SnapVaultTypography.bodyMedium,
            "bodySmall" to SnapVaultTypography.bodySmall,
            "labelMedium" to SnapVaultTypography.labelMedium,
            "labelSmall" to SnapVaultTypography.labelSmall,
        )

        val tooSmall = roles.filterValues { it.fontSize.value < MINIMUM_SP }
        assertTrue(
            tooSmall.isEmpty(),
            "roles below the ${MINIMUM_SP}sp floor: " +
                tooSmall.entries.joinToString { "${it.key}=${it.value.fontSize}" },
        )
    }

    // Every role sets its own line height on purpose. Leaving one unspecified silently falls
    // back to the font's natural leading, which is what makes a role's rhythm differ from
    // its neighbours for no visible reason.
    @Test
    fun everyRoleSetsAnExplicitLineHeight() {
        val roles: List<Pair<String, TextStyle>> = listOf(
            "headlineSmall" to SnapVaultTypography.headlineSmall,
            "titleLarge" to SnapVaultTypography.titleLarge,
            "titleMedium" to SnapVaultTypography.titleMedium,
            "titleSmall" to SnapVaultTypography.titleSmall,
            "bodyMedium" to SnapVaultTypography.bodyMedium,
            "bodySmall" to SnapVaultTypography.bodySmall,
            "labelMedium" to SnapVaultTypography.labelMedium,
            "labelSmall" to SnapVaultTypography.labelSmall,
        )
        val unset = roles.filter { (_, style) -> style.lineHeight == TextUnit.Unspecified }
        assertTrue(unset.isEmpty(), "roles with no line height: ${unset.map { it.first }}")
    }

    // The scale is only a single point of control while screens go through it.
    @Test
    fun screensDeclareNoFontSizeLiterals() {
        val offenders = uiSourceFiles().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> FONT_SIZE_LITERAL.containsMatchIn(line.substringBefore("//")) }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
        }
        assertTrue(
            offenders.isEmpty(),
            "fontSize literals in UI code — use MaterialTheme.typography instead:\n" +
                offenders.joinToString("\n").prependIndent("  "),
        )
    }

    private fun uiSourceFiles(): List<File> {
        val roots = listOf("commonMain", "desktopMain", "androidMain", "iosMain")
            .map { File("src/$it/kotlin/com/najdev/snapvault/ui") }
            .filter { it.isDirectory }
        check(roots.isNotEmpty()) { "no UI source roots found from ${File(".").absolutePath}" }
        return roots.flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
            .filterNot { it.parentFile.name == "theme" }
    }

    private companion object {
        const val MINIMUM_SP = 11f
        val FONT_SIZE_LITERAL = Regex("""fontSize = [0-9.]+\.sp""")
    }
}
