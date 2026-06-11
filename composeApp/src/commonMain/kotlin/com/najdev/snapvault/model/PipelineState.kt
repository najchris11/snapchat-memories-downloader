package com.najdev.snapvault.model

import kotlinx.serialization.Serializable

@Serializable
data class PhaseRecord(
    val completedAt: String,
    val stats: Map<String, Int> = emptyMap()
)

@Serializable
data class PipelineState(
    val extract: PhaseRecord? = null,
    val metadata: PhaseRecord? = null,
    val combine: PhaseRecord? = null
)
