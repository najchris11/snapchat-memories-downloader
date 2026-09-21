package com.najdev.snapvault.onboarding

import com.najdev.snapvault.loadOnboardingCompleted
import com.najdev.snapvault.saveOnboardingCompleted
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath

/**
 * The five things someone has to do between installing SnapVault and having a dated library,
 * in the order they have to do them.
 *
 * An enum rather than a list of data classes because the copy lives in `strings.xml` and the
 * order is the only thing code needs to reason about. `entries` is the single source for both
 * the stepper dots and the Back/Next arithmetic — they were free to disagree when the count
 * was a literal, which is the same mistake [com.najdev.snapvault.ui.DASHBOARD_STEP_COUNT]
 * exists to prevent on the Dashboard.
 */
enum class OnboardingStep {
    /** accounts.snapchat.com → My Data → Export your Memories. */
    RequestExport,

    /** Snapchat emails a link hours later; the zips come down unextracted. */
    DownloadZips,

    /** Which folder holds the zips, and where the finished library goes. */
    ChooseFolders,

    /** What each pipeline phase does, so the switches on the Dashboard mean something. */
    Pipeline,

    /** Press Start, read the log, open the Library. */
    RunIt,
}

val ONBOARDING_STEP_COUNT = OnboardingStep.entries.size

fun OnboardingStep.next(): OnboardingStep =
    OnboardingStep.entries.getOrElse(ordinal + 1) { this }

fun OnboardingStep.previous(): OnboardingStep =
    OnboardingStep.entries.getOrElse(ordinal - 1) { this }

/**
 * Whether the user has been through the flow.
 *
 * An interface for the same reason [com.najdev.snapvault.OutputFolderMemory] is one: the real
 * implementation reaches the machine's preference store, so a test taking the default would
 * read whatever this machine happens to hold and then write to it. [None] never shows the
 * flow and never persists, which is what UI tests that are not about onboarding pass.
 */
interface OnboardingMemory {
    fun isCompleted(): Boolean
    fun markCompleted()

    object Platform : OnboardingMemory {
        override fun isCompleted(): Boolean = loadOnboardingCompleted()
        override fun markCompleted() = saveOnboardingCompleted(true)
    }

    /** Behaves like a user who has already been through it: nothing shown, nothing written. */
    object None : OnboardingMemory {
        override fun isCompleted(): Boolean = true
        override fun markCompleted() = Unit
    }
}

/** An [OnboardingMemory] that keeps the flag in memory, for tests and previews. */
class InMemoryOnboardingMemory(private var completed: Boolean = false) : OnboardingMemory {
    override fun isCompleted(): Boolean = completed
    override fun markCompleted() { completed = true }
}

/**
 * Whether to open on the flow rather than the Dashboard.
 *
 * Deliberately only a function of the persisted flag: replaying from Settings drives the UI
 * directly instead of clearing it, so "show me that again" cannot re-arm the takeover on the
 * next launch.
 */
fun shouldShowOnboarding(memory: OnboardingMemory): Boolean = !memory.isCompleted()

/** What pressing "Create this folder" did. Reported to the user either way. */
sealed interface DropFolderOutcome {
    data object Created : DropFolderOutcome
    data object AlreadyExisted : DropFolderOutcome
    data class Failed(val reason: String) : DropFolderOutcome
}

/**
 * Creates the suggested drop folder, if it is not already there.
 *
 * Only ever called from an explicit click — the suggestion is rendered without touching the
 * disk, so someone who would rather choose their own folder never has one made for them.
 * Existing contents are left alone: by the time this is pressed a second time the folder may
 * already hold the user's zips.
 */
fun prepareDropFolder(fileSystem: FileSystem, path: String): DropFolderOutcome {
    if (path.isBlank()) return DropFolderOutcome.Failed("no suggested folder on this platform")
    val target = path.toPath()
    return try {
        when {
            fileSystem.metadataOrNull(target)?.isDirectory == true -> DropFolderOutcome.AlreadyExisted
            else -> {
                fileSystem.createDirectories(target, mustCreate = false)
                // createDirectories succeeds silently against an existing *file* on some
                // filesystems, so the folder is confirmed rather than assumed.
                if (fileSystem.metadataOrNull(target)?.isDirectory == true) {
                    DropFolderOutcome.Created
                } else {
                    DropFolderOutcome.Failed("a file already exists at that path")
                }
            }
        }
    } catch (e: IOException) {
        DropFolderOutcome.Failed(e.message ?: "could not create the folder")
    }
}
