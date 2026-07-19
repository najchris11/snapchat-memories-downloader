@file:JvmName("MediaScannerJvmShared")
package com.najdev.snapvault

import com.najdev.snapvault.model.FileMeta
import com.najdev.snapvault.ui.LibraryItem
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

actual fun scanMediaFiles(folderPath: String): List<LibraryItem> {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return emptyList()

    val index: Map<String, FileMeta> = runCatching {
        Json.decodeFromString<Map<String, FileMeta>>(
            File(folder, "vault_index.json").readText()
        )
    }.getOrDefault(emptyMap())

    val mediaExtensions = setOf("jpg", "jpeg", "png", "mp4", "mov", "gif")
    val videoExtensions = setOf("mp4", "mov")
    return (folder.listFiles() ?: return emptyList())
        .filter { it.isFile && it.extension.lowercase() in mediaExtensions }
        .sortedByDescending { it.lastModified() }
        .map { file ->
            val meta = index[file.name]
            LibraryItem(
                id = file.absolutePath,
                date = formatFileDate(file.lastModified()),
                title = file.nameWithoutExtension,
                type = if (file.extension.lowercase() in videoExtensions) "video" else "photo",
                duration = null,
                hasGps = meta?.hasGps ?: false,
                hasOverlay = meta?.hasOverlay ?: false,
                fileSizeBytes = file.length()
            )
        }
}

private fun formatFileDate(millis: Long): String =
    SimpleDateFormat("MMM dd, yyyy", Locale.US)
        .apply { timeZone = TimeZone.getDefault() }
        .format(Date(millis))
        .uppercase()
