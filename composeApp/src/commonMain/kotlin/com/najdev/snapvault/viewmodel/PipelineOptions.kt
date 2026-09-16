package com.najdev.snapvault.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Pipeline option defaults, named rather than inlined so they can be asserted. The N1
// blocker was a combination of three of these — a destructive step enabled, its preview
// off, and the whole card collapsed — and nothing would have caught a silent flip back.
internal const val DEFAULT_RUN_DOWNLOAD = true
internal const val DEFAULT_RUN_METADATA = true
internal const val DEFAULT_PRECISE_MATCHING = true
internal const val DEFAULT_RUN_COMBINE = true
internal const val DEFAULT_RUN_DEDUPE = true

// Deletion is an explicit opt-out: preview on, and the card open so the enabled steps are
// visible before Start is pressed.
internal const val DEFAULT_DRY_RUN = true
internal const val DEFAULT_PIPELINE_EXPANDED = true

// Option state lives in one holder rather than seven loose `var`s, so the controls, the
// action row and the status panel can be separate composables that the two layouts compose
// in a different order.
//
// Held by DashboardViewModel, which outlives any one screen. It was remembered inside
// DashboardScreen, so leaving the Dashboard discarded the user's choices and returning rebuilt
// the defaults — including re-enabling the combine step, which deletes originals (D22). A run
// takes a copy of these values when it starts; changing a switch mid-run shapes the next run,
// never the one in progress.
internal class PipelineOptions {
    var runDownload by mutableStateOf(DEFAULT_RUN_DOWNLOAD)
    var runMetadata by mutableStateOf(DEFAULT_RUN_METADATA)
    var preciseMatching by mutableStateOf(DEFAULT_PRECISE_MATCHING)
    var runCombine by mutableStateOf(DEFAULT_RUN_COMBINE)
    var runDedupe by mutableStateOf(DEFAULT_RUN_DEDUPE)
    var dryRun by mutableStateOf(DEFAULT_DRY_RUN)
    var expanded by mutableStateOf(DEFAULT_PIPELINE_EXPANDED)
}
