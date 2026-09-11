package com.najdev.snapvault.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stepper is the app's central status display, and it carried no semantics at all: step
 * number, active state and completion were conveyed only by fill, border and an icon swap,
 * so assistive tech saw four unlabelled shapes and a label.
 *
 * These pin the state sentence at each state, and pin the two layouts to the same sentence —
 * the compact stepper already said "Step 2 of 4" out loud, which is what made the expanded
 * one's silence visible.
 */
@OptIn(ExperimentalTestApi::class)
class StepperSemanticsTest {

    private fun stateOf(label: String, content: @androidx.compose.runtime.Composable () -> Unit): String {
        var state = ""
        runComposeUiTest {
            setContent { SnapVaultTheme(darkMode = true) { content() } }
            state = onNodeWithText(label, substring = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription]
        }
        return state
    }

    @Test
    fun anActiveStepAnnouncesItsPositionAndThatItIsRunning() {
        assertEquals(
            "Step 2 of 4, in progress",
            stateOf("Downloading") {
                StepItem(2, "Downloading", active = true, complete = false, icon = Icons.Outlined.CloudSync)
            },
        )
    }

    @Test
    fun aStepThatHasNotStartedSaysSo() {
        assertEquals(
            "Step 3 of 4, not started",
            stateOf("Processing") {
                StepItem(3, "Processing", active = false, complete = false, icon = Icons.Outlined.CloudSync)
            },
        )
    }

    @Test
    fun aFinishedStepSaysSo() {
        assertEquals(
            "Step 1 of 4, complete",
            stateOf("Setup") {
                StepItem(1, "Setup", active = false, complete = true, icon = Icons.Outlined.CloudSync)
            },
        )
    }

    // A run can finish while reporting failures. The circle turns amber; nothing said it.
    @Test
    fun aFinishedStepWithFailuresDoesNotAnnounceAPlainSuccess() {
        assertEquals(
            "Step 4 of 4, complete with warnings",
            stateOf("Complete") {
                StepItem(
                    4, "Complete", active = false, complete = true,
                    icon = Icons.Outlined.CloudSync, warning = true,
                )
            },
        )
    }

    // Compact and expanded are two renderings of one state. If they can drift, the phone
    // layout will quietly stop matching the desktop one.
    @Test
    fun bothLayoutsAnnounceTheSameState() {
        val expanded = stateOf("Downloading") {
            StepItem(2, "Downloading", active = true, complete = false, icon = Icons.Outlined.CloudSync)
        }
        val compact = stateOf("Step 2 of 4") {
            CompactStepper(currentStep = 1, hasWarnings = false)
        }
        assertEquals(expanded, compact)
    }

    // The ring is the only thing on screen that reports how far along the run is. Without a
    // range info it is a decoration, and without a label it is an anonymous progress bar.
    @Test
    fun theProgressRingReportsItsValue() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                PipelineProgressRing(progress = 0.42f, indeterminate = false)
            }
        }

        onNode(hasContentDescription("Overall progress"))
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.42f, 0f..1f))
    }

    @Test
    fun theIndeterminateRingReportsThatItIsIndeterminate() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                PipelineProgressRing(progress = 0f, indeterminate = true)
            }
        }

        onNode(hasContentDescription("Overall progress"))
            .assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
    }
}
