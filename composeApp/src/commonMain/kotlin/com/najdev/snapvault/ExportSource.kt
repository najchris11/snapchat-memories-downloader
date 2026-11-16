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

/**
 * The name a person recognises a selected source by: its file name. Filesystem paths are cut
 * to their last segment; Android document IDs ("raw%3A%2Fstorage%2F…%2Fexport.zip") are decoded
 * first, because their slashes are percent-encoded and would otherwise survive the cut. A '%'
 * that does not start a valid escape is kept as typed.
 */
fun sourceDisplayName(path: String): String {
    val trimmed = path.trimEnd('/', '\\')
    return percentDecode(trimmed.substringAfterLast('/').substringAfterLast('\\'))
        .substringAfterLast('/').substringAfterLast(':')
}

private fun percentDecode(text: String): String {
    if ('%' !in text) return text
    val bytes = ArrayList<Byte>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val hex = if (c == '%' && i + 2 <= text.lastIndex) text.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (hex != null) {
            bytes += hex.toByte(); i += 3
        } else {
            c.toString().encodeToByteArray().forEach { bytes += it }; i++
        }
    }
    return bytes.toByteArray().decodeToString()
}
