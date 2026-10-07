package com.najdev.snapvault.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.ImportMode
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
    fun combiningDisclosesSourceCleanupBeforeStart() = runComposeUiTest {
        // Combine removes originals by design; its label previously mentioned only videos
        // and gave no indication that enabling it also authorized source-pair cleanup.
        val viewModel = idleDashboardViewModel()
        setContent {
            SnapVaultTheme(darkMode = true) { DashboardScreen(viewModel = viewModel, onNavigateToSettings = {}) }
        }
        val notice = stringResource("opt_combine_cleanup_helper")
        onNodeWithText("Combine photo and video overlays").assertIsDisplayed()
        onNodeWithText(notice).assertIsDisplayed()
        onNode(hasText("Combine photo and video overlays") and isToggleable()).performClick()
        waitForIdle()
        onAllNodes(hasText(notice)).assertCountEquals(0)
        viewModel.dispose()
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

    // The capability and dependency banners use InlineBanner without an action; it must not
    // render a phantom button there — that was the N2 problem in the top bar.
    @Test
    fun bannerWithoutAnActionRendersNoButton() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                InlineBanner(
                    icon = Icons.Outlined.Info,
                    accent = SnapVaultColors.warning,
                    title = "Limited media support",
                    body = "Video overlays are not combined on this build.",
                )
            }
        }

        onNodeWithText("Limited media support").assertIsDisplayed()
        onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    // D10: a run can now finish with warnings and no failures. The banner only knew how to say
    // "N step(s) failed", which over those runs would have read "0 step(s) failed" — a headline
    // that is both alarming and wrong.
    @Test
    fun aRunWithOnlyWarningsIsNotHeadlinedAsFailedSteps() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                RunOutcomeBanner(failureCount = 0, warningCount = 3, onViewLog = {})
            }
        }

        onNodeWithText("Finished with 3 warnings").assertIsDisplayed()
        onAllNodes(hasText("step", substring = true)).assertCountEquals(0)
    }

    // failureCount sums failed files and writes across every phase, so the banner used to read
    // "47 steps failed" for a run that has four steps. It must not call the count steps.
    @Test
    fun failuresLeadTheBannerWhenThereAreAny() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                RunOutcomeBanner(failureCount = 47, warningCount = 4, onViewLog = {})
            }
        }

        onNodeWithText("Finished with 47 failures").assertIsDisplayed()
        onAllNodes(hasText("step", substring = true)).assertCountEquals(0)
    }

    @Test
    fun aSingleFailureIsSingular() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                RunOutcomeBanner(failureCount = 1, warningCount = 0, onViewLog = {})
            }
        }

        onNodeWithText("Finished with 1 failure").assertIsDisplayed()
    }

    // The ZIP metadata switch read "Write Date Metadata" while precise matching — on by
    // default — wrote each photo's GPS position too. Its label has to follow what it writes.
    @Test
    fun theZipMetadataSwitchSaysLocationWheneverItWritesOne() = runComposeUiTest {
        val withLocation = stringResource("opt_write_location_metadata")
        val dateOnly = stringResource("opt_write_date_metadata")
        val viewModel = idleDashboardViewModel()
        setContent {
            SnapVaultTheme(darkMode = true) { DashboardScreen(viewModel = viewModel, onNavigateToSettings = {}) }
        }

        // ZIP import, defaults: precise matching is on, so a location is written.
        onNodeWithText(withLocation).assertIsDisplayed()
        onAllNodes(hasText(dateOnly)).assertCountEquals(0)

        onNode(hasText("Precise time + GPS matching") and isToggleable()).performClick()
        waitForIdle()
        onNodeWithText(dateOnly).assertIsDisplayed()
        onAllNodes(hasText(withLocation)).assertCountEquals(0)

        // Legacy always writes the export's location.
        viewModel.changeImportMode(ImportMode.Legacy)
        waitForIdle()
        onNodeWithText(withLocation).assertIsDisplayed()
        viewModel.dispose()
    }

    // Step two extracts in ZIP mode and only downloads in Legacy mode, but it said
    // "Downloading" in both — on the recommended path, a step that downloads nothing.
    @Test
    fun stepTwoIsNamedForWhatTheImportModeDoes() = runComposeUiTest {
        var mode by mutableStateOf(ImportMode.Zip)
        setContent {
            SnapVaultTheme(darkMode = true) {
                CompactStepper(currentStep = 1, hasWarnings = false, importMode = mode)
            }
        }

        onNode(hasText("Extracting", substring = true)).assertIsDisplayed()
        onAllNodes(hasText("Downloading", substring = true)).assertCountEquals(0)

        mode = ImportMode.Legacy
        waitForIdle()
        onNode(hasText("Downloading", substring = true)).assertIsDisplayed()
    }

    // D18: precise matching writes each memory's GPS position into the file itself, and it is
    // on by default. Nothing said so, and a location inside a photo travels with every copy of
    // it that is shared. The notice has to be on screen before Start can be pressed at all —
    // with nothing picked yet — and has to follow the options that actually write a location.
    @Test
    fun theLocationNoticeIsShownBeforeAnImportCanStartAndOnlyWhenGpsWillBeWritten() = runComposeUiTest {
        val notice = stringResource("opt_gps_disclosure")
        val viewModel = idleDashboardViewModel()
        setContent {
            SnapVaultTheme(darkMode = true) { DashboardScreen(viewModel = viewModel, onNavigateToSettings = {}) }
        }

        // ZIP import, defaults: precise time + GPS matching is on.
        onNode(hasText("Start", substring = true) and hasClickAction()).assertIsNotEnabled()
        onNodeWithText(notice).assertIsDisplayed()

        // Date-only metadata writes no location, so the notice would be false.
        onNode(hasText("Precise time + GPS matching") and isToggleable()).performClick()
        waitForIdle()
        onAllNodes(hasText(notice)).assertCountEquals(0)

        // Legacy import writes GPS from the export's location data whenever metadata is on.
        viewModel.changeImportMode(ImportMode.Legacy)
        waitForIdle()
        onNodeWithText(notice).assertIsDisplayed()
        viewModel.dispose()
    }
}
