package com.najdev.snapvault.onboarding

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The flow exists for the user who installed SnapVault without watching anything first, so the
 * two behaviours that matter are "it appears exactly once on its own" and "it can always be
 * summoned back". Both are decisions about persisted state rather than about pixels, so they
 * live here rather than in the Compose test.
 */
class OnboardingTest {

    @Test
    fun firstLaunchShowsTheFlow() {
        val memory = InMemoryOnboardingMemory(completed = false)
        assertTrue(shouldShowOnboarding(memory), "a fresh install must land on the flow")
    }

    // Skip is a completion, not a "remind me next time". Treating it as anything else means
    // the user who deliberately dismissed it is shown it again on every launch.
    @Test
    fun skippingCountsAsSeenSoItDoesNotReturnNextLaunch() {
        val memory = InMemoryOnboardingMemory(completed = false)
        memory.markCompleted()
        assertFalse(shouldShowOnboarding(memory), "skip must persist like finishing does")
    }

    @Test
    fun replayFromSettingsDoesNotResurrectItOnTheNextLaunch() {
        // Replaying is a one-off view, so it must not clear the persisted flag — otherwise
        // "show me that again" silently re-arms the first-launch takeover.
        val memory = InMemoryOnboardingMemory(completed = true)
        assertFalse(shouldShowOnboarding(memory))
        assertTrue(memory.isCompleted(), "replay must leave the completed flag alone")
    }

    @Test
    fun theStepsAreOrderedAndCounted() {
        assertEquals(
            listOf(
                OnboardingStep.RequestExport,
                OnboardingStep.DownloadZips,
                OnboardingStep.ChooseFolders,
                OnboardingStep.Pipeline,
                OnboardingStep.RunIt,
            ),
            OnboardingStep.entries.toList(),
            "the stepper dots and the Back/Next arithmetic both read this order",
        )
        assertEquals(ONBOARDING_STEP_COUNT, OnboardingStep.entries.size)
    }

    @Test
    fun nextAndBackStopAtTheEnds() {
        assertEquals(OnboardingStep.DownloadZips, OnboardingStep.RequestExport.next())
        assertEquals(OnboardingStep.RequestExport, OnboardingStep.RequestExport.previous())
        assertEquals(OnboardingStep.RunIt, OnboardingStep.RunIt.next())
        assertEquals(OnboardingStep.Pipeline, OnboardingStep.RunIt.previous())
    }

    // The folder offer writes to the user's disk, so it only ever runs from an explicit click.
    // These cover what that click does.
    @Test
    fun creatingTheDropFolderMakesItAndReportsTheCreation() {
        val fs = FakeFileSystem()
        fs.createDirectories("/home/user/Downloads".toPath())

        val outcome = prepareDropFolder(fs, "/home/user/Downloads/SnapVault Exports")

        assertEquals(DropFolderOutcome.Created, outcome)
        assertTrue(fs.exists("/home/user/Downloads/SnapVault Exports".toPath()))
    }

    // Re-pressing must not read as a failure, and must not disturb zips already dropped in.
    @Test
    fun anExistingDropFolderIsReusedNotReplaced() {
        val fs = FakeFileSystem()
        val dir = "/home/user/Downloads/SnapVault Exports".toPath()
        fs.createDirectories(dir)
        fs.write(dir.resolve("memories-1.zip")) { writeUtf8("already here") }

        val outcome = prepareDropFolder(fs, dir.toString())

        assertEquals(DropFolderOutcome.AlreadyExisted, outcome)
        assertTrue(fs.exists(dir.resolve("memories-1.zip")), "an existing drop folder must not be cleared")
    }

    @Test
    fun anUncreatableDropFolderReportsFailureRatherThanThrowing() {
        val fs = FakeFileSystem()
        // A regular file where the folder should go: createDirectories cannot succeed here.
        fs.createDirectories("/home/user/Downloads".toPath())
        fs.write("/home/user/Downloads/SnapVault Exports".toPath()) { writeUtf8("not a directory") }

        val outcome = prepareDropFolder(fs, "/home/user/Downloads/SnapVault Exports")

        assertTrue(outcome is DropFolderOutcome.Failed, "was $outcome — a refused mkdir must not escape as an exception")
    }

    @Test
    fun aBlankSuggestionIsNotTreatedAsAPath() {
        // Mobile has no user-visible drop folder, so the suggestion is absent there and the
        // step renders instructions only. Asking to create "" must not reach the filesystem.
        val fs = FakeFileSystem()
        assertTrue(prepareDropFolder(fs, "   ") is DropFolderOutcome.Failed)
        assertTrue(prepareDropFolder(fs, "") is DropFolderOutcome.Failed)
    }
}
