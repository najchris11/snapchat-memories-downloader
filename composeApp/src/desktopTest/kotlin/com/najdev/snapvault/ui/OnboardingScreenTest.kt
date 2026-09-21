package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.onboarding.DropFolderOutcome
import com.najdev.snapvault.onboarding.ONBOARDING_STEP_COUNT
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The flow's whole job is to be readable by someone who has never used SnapVault, so the
 * behaviours worth pinning are the ones that would strand that person: a Next that does not
 * advance, a Skip that does not leave, and a folder offer that writes to disk before it is
 * asked to.
 */
@OptIn(ExperimentalTestApi::class)
class OnboardingScreenTest {

    private fun stringResource(key: String): String {
        val stringsXml = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
            .let { File(it, "composeApp/src/commonMain/composeResources/values/strings.xml") }
        return Regex("""<string name="$key">([^<]*)</string>""").find(stringsXml.readText())
            ?.groupValues?.get(1)
            ?: error("no string resource named '$key'")
    }

    @Test
    fun nextWalksEveryStepAndFinishesOnce() {
        var finished = 0
        runComposeUiTest {
            setContent {
                SnapVaultTheme(darkMode = true) {
                    OnboardingScreen(
                        onFinish = { finished++ },
                        onSkip = {},
                        dropFolderSuggestion = null,
                        onCreateDropFolder = { DropFolderOutcome.Created },
                        canRevealFolder = false,
                        onOpenUrl = {},
                    )
                }
            }
            onNodeWithText(stringResource("onb_request_title")).assertIsDisplayed()

            // Walk to the last step. Finish only exists there, so reaching it is the assertion.
            repeat(ONBOARDING_STEP_COUNT - 1) {
                onNodeWithText(stringResource("onb_next")).performClick()
            }
            onNodeWithText(stringResource("onb_run_title")).assertIsDisplayed()
            onNodeWithText(stringResource("onb_finish")).performClick()
        }
        assertEquals(1, finished, "Finish on the last step must complete the flow exactly once")
    }

    // Back used to be the easy thing to get wrong: on step one it has nowhere to go, and an
    // enabled control that does nothing reads as a broken button.
    @Test
    fun backIsDisabledOnTheFirstStepAndReturnsAfterwards() {
        runComposeUiTest {
            setContent {
                SnapVaultTheme(darkMode = true) {
                    OnboardingScreen(
                        onFinish = {}, onSkip = {},
                        dropFolderSuggestion = null,
                        onCreateDropFolder = { DropFolderOutcome.Created },
                        canRevealFolder = false,
                        onOpenUrl = {},
                    )
                }
            }
            onNodeWithText(stringResource("onb_back")).assertIsNotEnabled()
            onNodeWithText(stringResource("onb_next")).performClick()
            onNodeWithText(stringResource("onb_back")).assertIsEnabled()
            onNodeWithText(stringResource("onb_back")).performClick()
            onNodeWithText(stringResource("onb_request_title")).assertIsDisplayed()
        }
    }

    @Test
    fun skipIsReachableFromTheFirstStep() {
        var skipped = 0
        runComposeUiTest {
            setContent {
                SnapVaultTheme(darkMode = true) {
                    OnboardingScreen(
                        onFinish = {}, onSkip = { skipped++ },
                        dropFolderSuggestion = null,
                        onCreateDropFolder = { DropFolderOutcome.Created },
                        canRevealFolder = false,
                        onOpenUrl = {},
                    )
                }
            }
            onNodeWithText(stringResource("onb_skip")).performClick()
        }
        assertEquals(1, skipped, "someone who arrived from the video must be able to leave at once")
    }

    // The folder offer is the only part of the flow that touches the user's disk. Rendering
    // the suggestion must not be enough to create it — that was the whole point of choosing
    // "offer, never auto-create".
    @Test
    fun theDropFolderIsNotCreatedUntilTheButtonIsPressed() {
        val requested = mutableListOf<String>()
        runComposeUiTest {
            setContent {
                SnapVaultTheme(darkMode = true) {
                    OnboardingScreen(
                        onFinish = {}, onSkip = {},
                        dropFolderSuggestion = "/tmp/snapvault-test-drop",
                        onCreateDropFolder = { path -> requested += path; DropFolderOutcome.Created },
                        canRevealFolder = false,
                        onOpenUrl = {},
                    )
                }
            }
            onNodeWithText(stringResource("onb_next")).performClick()
            onNodeWithText("/tmp/snapvault-test-drop").assertIsDisplayed()
            assertTrue(requested.isEmpty(), "showing the suggestion must not create it: $requested")

            onNodeWithText(stringResource("onb_download_folder_create")).performClick()
            waitForIdle()
        }
        assertEquals(listOf("/tmp/snapvault-test-drop"), requested)
    }

    // A failed mkdir has to say so and leave the user a way forward, rather than looking like
    // nothing happened.
    @Test
    fun aFailedFolderCreationIsReportedNotSwallowed() {
        runComposeUiTest {
            setContent {
                SnapVaultTheme(darkMode = true) {
                    OnboardingScreen(
                        onFinish = {}, onSkip = {},
                        dropFolderSuggestion = "/nope/snapvault",
                        onCreateDropFolder = { DropFolderOutcome.Failed("permission denied") },
                        canRevealFolder = false,
                        onOpenUrl = {},
                    )
                }
            }
            onNodeWithText(stringResource("onb_next")).performClick()
            onNodeWithText(stringResource("onb_download_folder_create")).performClick()
            waitForIdle()
            onNode(hasText("permission denied", substring = true)).assertIsDisplayed()
        }
    }

    // Mobile has no drop folder to offer. The step still has to say something useful rather
    // than rendering an empty card where the offer would be.
    @Test
    fun withoutASuggestionTheStepFallsBackToInstructions() {
        runComposeUiTest {
            setContent {
                SnapVaultTheme(darkMode = true) {
                    OnboardingScreen(
                        onFinish = {}, onSkip = {},
                        dropFolderSuggestion = null,
                        onCreateDropFolder = { DropFolderOutcome.Created },
                        canRevealFolder = false,
                        windowSize = WindowSize.Compact,
                        onOpenUrl = {},
                    )
                }
            }
            onNodeWithText(stringResource("onb_next")).performClick()
            onNodeWithText(stringResource("onb_download_folder_mobile")).assertIsDisplayed()
        }
    }

    // Regression guard for the reason this flow exists at all: the pipeline step is the only
    // place the destructive phase is explained before the user meets its switch.
    @Test
    fun thePipelineStepWarnsThatDedupeDeletes() {
        val body = stringResource("onb_pipeline_dedupe_body").lowercase()
        assertTrue(
            "preview" in body,
            "the dedupe explanation must name the preview that protects the first run: '$body'",
        )
        assertTrue(
            "delete" in body || "removes" in body,
            "the dedupe explanation must say it deletes: '$body'",
        )
    }

    // The one link in the flow that strands every new user if it is wrong: the bare host
    // lands on a generic account page with no visible route to the export request, so it has
    // to be the deep link. Pinned because a well-meaning tidy-up to the shorter URL reads as
    // an improvement and silently costs the user the step this whole flow exists to explain.
    @Test
    fun theRequestLinkGoesStraightToTheDownloadMyDataPage() {
        assertEquals(
            "https://accounts.snapchat.com/v2/download-my-data",
            stringResource("onb_request_url"),
        )
    }

    @Test
    fun theRequestButtonOpensThatLinkRatherThanJustDescribingIt() {
        val opened = mutableListOf<String>()
        runComposeUiTest {
            setContent {
                SnapVaultTheme(darkMode = true) {
                    OnboardingScreen(
                        onFinish = {}, onSkip = {},
                        dropFolderSuggestion = null,
                        onCreateDropFolder = { DropFolderOutcome.Created },
                        canRevealFolder = false,
                        onOpenUrl = { opened += it },
                    )
                }
            }
            onNodeWithText(stringResource("onb_request_action")).performClick()
        }
        assertEquals(listOf("https://accounts.snapchat.com/v2/download-my-data"), opened)
    }
}
