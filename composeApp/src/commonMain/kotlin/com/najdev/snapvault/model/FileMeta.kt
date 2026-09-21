package com.najdev.snapvault.model

import kotlinx.serialization.Serializable

/**
 * One entry in `vault_index.json`.
 *
 * [favorited] **must** keep its default. kotlinx.serialization requires a value for every
 * constructor parameter without one, so a field added without a default makes every index
 * written before it fail to parse — and that parse sits inside a `runCatching { … }
 * .getOrDefault(emptyMap())`, so the failure would be silent: the Library would simply report
 * every memory as having no GPS and no overlay.
 *
 * It is also the only user-owned field here. [hasGps] and [hasOverlay] are recomputable by
 * re-running the pipeline; a favorite is not. See [com.najdev.snapvault.VaultIndex].
 */
/*
 * [hasOverlay] means the memory came with an overlay file; [combined] means that overlay has
 * actually been burned into *this* file. They were one flag, set when the pair was found, and
 * the Library drew it as "Combined" — so a run with combining off, or a combine that failed,
 * showed untouched originals as combined (D11). [combined] defaults to false, which also makes
 * every index written before it read truthfully rather than repeating the old claim.
 */
@Serializable
data class FileMeta(
    val hasGps: Boolean,
    val hasOverlay: Boolean,
    val favorited: Boolean = false,
    val combined: Boolean = false,
)
