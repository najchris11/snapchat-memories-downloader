package com.najdev.snapvault.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.viewmodel.DEFAULT_DRY_RUN
import com.najdev.snapvault.viewmodel.DEFAULT_PIPELINE_EXPANDED
import com.najdev.snapvault.ui.theme.SnapVaultColors
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The UI layer had no test coverage before this round, so every layout and state-default
// regression had to be caught by eye. These cover the ones with real consequences: a
// destructive default, and a failure state that used to be invisible.
@OptIn(ExperimentalTestApi::class)
class DashboardScreenTest {

    // Regression for N3. Medium keeps the 220dp sidebar, leaving too little room for the
    // Dashboard's two-column layout and four-circle stepper. The original responsive change
    // stacked only Compact, despite the audit requiring the compact status layout at Medium.
    @Test
    fun mediumUsesTheStackedDashboardLayout() {
        assertTrue(usesCompactDashboardLayout(WindowSize.Compact))
        assertTrue(usesCompactDashboardLayout(WindowSize.Medium))
        assertFalse(usesCompactDashboardLayout(WindowSize.Expanded))
    }

    // Regression for N1. pipelineExpanded defaulted false while runDedupe was true and
    // dryRun false, so pressing Start Download could delete files the user had never been
    // shown an option for. Both halves have to hold: preview on, card open.
    @Test
    fun deletionIsAnExplicitOptOutNotAnUnseenDefault() {
        assertTrue(
            DEFAULT_DRY_RUN,
            "dryRun must default on, or enabling dedupe deletes files with no preview",
        )
        assertTrue(
            DEFAULT_PIPELINE_EXPANDED,
            "Pipeline Options must start expanded, or an enabled dedupe step is invisible",
        )
    }

    private fun stringResource(key: String): String {
        val stringsXml = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
            .let { java.io.File(it, "composeApp/src/commonMain/composeResources/values/strings.xml") }
        return Regex("""<string name="$key">([^<]*)</string>""").find(stringsXml.readText())
            ?.groupValues?.get(1)
            ?: error("no string resource named '$key'")
    }

    // Regression (D09). The switch said "nothing deleted", but only deduplication honours
    // it: combine deleted the main/overlay originals and legacy extraction deleted the
    // archives regardless. An ordinary user reading a global promise of a non-destructive
    // preview and pressing Start lost files the preview never covered. The wording has to
    // describe the one step it actually controls.
    @Test
    fun dryRunPromiseIsScopedToDuplicates() {
        val label = stringResource("opt_dedupe_dry_run")
        assertFalse(
            "nothing deleted" in label.lowercase(),
            "only dedupe honours this switch, so it cannot promise a global preview: '$label'",
        )
        assertTrue(
            "duplicate" in label.lowercase(),
            "the label must name what it previews: '$label'",
        )

        val helper = stringResource("opt_dedupe_dry_run_helper")
        assertTrue(
            "still run" in helper.lowercase(),
            "helper text must say the other steps are unaffected: '$helper'",
        )
    }

    @Test
    fun dryRunTogglePresentsItselfAsOn() = runComposeUiTest {
        val label = stringResource("opt_dedupe_dry_run")
        val helper = stringResource("opt_dedupe_dry_run_helper")
        setContent {
            SnapVaultTheme(darkMode = true) {
                PipelineItem(
                    icon = Icons.Outlined.Info,
                    label = label,
                    checked = DEFAULT_DRY_RUN,
                    onCheckedChange = {},
                    helperText = helper,
                )
            }
        }

        onNodeWithText(label).assertIsDisplayed()
        // The scope caveat is the whole point of the rewording; it has to be on screen,
        // not only in the resource file.
        onNodeWithText(helper).assertIsDisplayed()
        onNode(isToggleable()).assertIsOn()
    }

    // Regression for N5. hasWarnings only tinted step 4's circle, so a run that reported
    // failures rendered as a slightly different shade of success. The count has to be on
    // screen, and the log has to be reachable from it.
    @Test
    fun failureBannerShowsTheCountAndAWayIntoTheLog() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                InlineBanner(
                    icon = Icons.Outlined.Info,
                    accent = SnapVaultColors.warning,
                    title = "24885 step(s) failed",
                    body = "Some files were not processed.",
                    actionLabel = "View log",
                    onAction = {},
                )
            }
        }

        onNodeWithText("24885 step(s) failed").assertIsDisplayed()
        onNodeWithText("View log").assertIsDisplayed()
        onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    // The Android and dependency banners use InlineBanner without an action; it must not
    // render a phantom button there — that was the N2 problem in the top bar.
    @Test
    fun bannerWithoutAnActionRendersNoButton() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                InlineBanner(
                    icon = Icons.Outlined.Info,
                    accent = SnapVaultColors.warning,
                    title = "Android Preview",
                    body = "Video overlay combining is not yet implemented on Android.",
                )
            }
        }

        onNodeWithText("Android Preview").assertIsDisplayed()
        onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}
