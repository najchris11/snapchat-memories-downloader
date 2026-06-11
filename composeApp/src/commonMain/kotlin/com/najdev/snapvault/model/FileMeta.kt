package com.najdev.snapvault.model

import kotlinx.serialization.Serializable

@Serializable
data class FileMeta(
    val hasGps: Boolean,
    val hasOverlay: Boolean,
    val metadataWritten: Boolean = false
)
