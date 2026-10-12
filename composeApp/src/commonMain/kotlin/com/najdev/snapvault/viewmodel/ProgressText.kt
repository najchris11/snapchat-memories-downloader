package com.najdev.snapvault.viewmodel

import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import snapchat_memories_downloader.composeapp.generated.resources.*

private const val DONE_TOKEN = "{done}"
private const val TOTAL_TOKEN = "{total}"

/** A "done / total" progress template, resolved once so per-file callbacks can fill it in. */
internal class CountTemplate(private val text: String) {
    operator fun invoke(done: Int, total: Int): String =
        text.replace(DONE_TOKEN, done.toString()).replace(TOTAL_TOKEN, total.toString())
}

/** A counted log noun resolved before entering a plain callback. */
internal class CountedNoun(private val one: String, private val other: String) {
    operator fun invoke(count: Int): String =
        (if (count == 1) one else other).replace(COUNT_TOKEN, count.toString())

    companion object {
        private const val COUNT_TOKEN = "314159265"

        suspend fun load(resource: PluralStringResource) = CountedNoun(
            getPluralString(resource, 1, 314159265),
            getPluralString(resource, 2, 314159265),
        )
    }
}

/**
 * The run's progress line, resolved from strings.xml up front.
 *
 * The per-file callbacks that move the progress line are plain lambdas, and resource lookups
 * suspend, so the wording is loaded once when a run starts and the callbacks only fill in
 * counts. Tests read the same resolved text a user sees.
 */
internal class ProgressText private constructor(
    val readingMemories: String,
    val extractingFiles: String,
    val extractingArchives: String,
    val combiningOverlays: String,
    val taggingCombined: String,
    val dedupeScanning: String,
    val runComplete: String,
    val runCompleteWithWarnings: String,
    val cancelled: String,
    val failed: String,
    val stopping: String,
    val extracting: CountTemplate,
    val metadata: CountTemplate,
    val combining: CountTemplate,
    val files: CountedNoun,
    val overlayPairs: CountedNoun,
    val errors: CountedNoun,
    val videoEncodes: CountedNoun,
    private val duplicatesFound: String,
    private val duplicatesRemoved: String,
) {
    fun duplicates(count: Int, preview: Boolean): String =
        (if (preview) duplicatesFound else duplicatesRemoved).replace(DONE_TOKEN, count.toString())

    /** Plural by the total: the noun agrees with how many files the phase has, not how many are done. */
    suspend fun downloading(total: Int): CountTemplate =
        CountTemplate(getPluralString(Res.plurals.progress_downloading_count, total, DONE_TOKEN, TOTAL_TOKEN))

    companion object {
        suspend fun load() = ProgressText(
            readingMemories = getString(Res.string.progress_reading_memories),
            extractingFiles = getString(Res.string.progress_extracting_files),
            extractingArchives = getString(Res.string.progress_extracting_archives),
            combiningOverlays = getString(Res.string.progress_combining_overlays),
            taggingCombined = getString(Res.string.progress_tagging_combined),
            dedupeScanning = getString(Res.string.progress_deduping_scanning),
            runComplete = getString(Res.string.progress_run_complete),
            runCompleteWithWarnings = getString(Res.string.progress_run_complete_warnings),
            cancelled = getString(Res.string.progress_cancelled),
            failed = getString(Res.string.progress_failed),
            stopping = getString(Res.string.progress_stopping),
            extracting = CountTemplate(getString(Res.string.progress_extracting_count, DONE_TOKEN, TOTAL_TOKEN)),
            metadata = CountTemplate(getString(Res.string.progress_metadata_count, DONE_TOKEN, TOTAL_TOKEN)),
            combining = CountTemplate(getString(Res.string.progress_combining_count, DONE_TOKEN, TOTAL_TOKEN)),
            files = CountedNoun.load(Res.plurals.count_files),
            overlayPairs = CountedNoun.load(Res.plurals.count_overlay_pairs),
            errors = CountedNoun.load(Res.plurals.count_errors),
            videoEncodes = CountedNoun.load(Res.plurals.count_video_encodes),
            duplicatesFound = getString(Res.string.progress_duplicates_found, DONE_TOKEN),
            duplicatesRemoved = getString(Res.string.progress_duplicates_removed, DONE_TOKEN),
        )
    }
}

/** "1 file" / "2 files" — for log lines that used to say "file(s)". */
internal suspend fun plural(resource: PluralStringResource, count: Int): String =
    getPluralString(resource, count, count)
