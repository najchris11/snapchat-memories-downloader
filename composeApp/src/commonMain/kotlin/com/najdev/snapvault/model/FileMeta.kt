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
 * re-running the pipeline; a favourite is not. See [com.najdev.snapvault.VaultIndex].
 */
@Serializable
data class FileMeta(
    val hasGps: Boolean,
    val hasOverlay: Boolean,
    val favorited: Boolean = false,
)
