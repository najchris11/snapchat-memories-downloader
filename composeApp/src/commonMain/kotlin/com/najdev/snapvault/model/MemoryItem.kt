package com.najdev.snapvault.model

import kotlinx.serialization.Serializable

@Serializable
data class MemoryItem(
    val id: String,
    val url: String,
    val isGet: Boolean,
    val dateStr: String?,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val downloadedPath: String? = null,
    val isDownloaded: Boolean = false,
    val metadataWritten: Boolean = false,
    val isOverlayCombined: Boolean = false
)

@Serializable
data class LogMessage(
    val type: String, // "info", "error", "success", "log", "raw"
    val message: String,
    val timestamp: String
)
