package com.najdev.snapvault

import okio.FileSystem
import okio.Path.Companion.toPath

sealed interface ExportSource {
    val path: String
    data class ZipFile(override val path: String) : ExportSource
    data class ZipFolder(override val path: String) : ExportSource
    data class History(override val path: String) : ExportSource
}

/** Inspect only the selected directory and Snapchat's known history subdirectories. */
fun detectExportSource(fileSystem: FileSystem, selectedPath: String): ExportSource? {
    val path = selectedPath.toPath()
    val metadata = fileSystem.metadataOrNull(path) ?: return null
    if (metadata.isRegularFile) return when (path.name.substringAfterLast('.').lowercase()) {
        "zip" -> ExportSource.ZipFile(selectedPath)
        "json", "html" -> ExportSource.History(selectedPath)
        else -> null
    }
    if (!metadata.isDirectory) return null
    if (fileSystem.list(path).any {
            it.name.endsWith(".zip", ignoreCase = true) && fileSystem.metadata(it).isRegularFile
        }) return ExportSource.ZipFolder(selectedPath)
    for (relative in listOf("memories_history.json", "json/memories_history.json", "memories_history.html", "html/memories_history.html")) {
        val history = path / relative
        if (fileSystem.metadataOrNull(history)?.isRegularFile == true) return ExportSource.History(history.toString())
    }
    return null
}
