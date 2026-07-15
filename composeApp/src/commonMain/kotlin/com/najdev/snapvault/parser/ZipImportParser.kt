package com.najdev.snapvault.parser

import com.najdev.snapvault.listZipEntries

data class HtmlMemoryEntry(
    val fileName: String,
    val uuid: String,
    val date: String,
    val isVideo: Boolean,
    val hasOverlay: Boolean,
    val overlayFileName: String?
)

object ZipImportParser {

    private val nameRegex = Regex(
        """^(\d{4}-\d{2}-\d{2})_([A-Za-z0-9\-]+)-(main|overlay)\.(\w+)$"""
    )

    // Matches files that have the date-UUID prefix but no -main/-overlay suffix.
    // Older or non-standard Snapchat exports occasionally produce these; treat them
    // as standalone main files with no overlay pairing.
    private val fallbackRegex = Regex(
        """^(\d{4}-\d{2}-\d{2})_([A-Za-z0-9\-]+)\.(\w+)$"""
    )

    private val videoExtensions = setOf("mp4", "mov", "avi", "mkv", "m4v")

    // One filename parsed by either nameRegex or fallbackRegex, with everything the
    // entry-building pass needs — no regex re-matching happens after this point.
    private data class ParsedName(
        val fileName: String,
        val date: String,
        val uuid: String,
        val type: String, // "main" or "overlay"
        val ext: String,
    )

    private fun parseName(fileName: String): ParsedName? {
        nameRegex.find(fileName)?.let { m ->
            return ParsedName(fileName, m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4].lowercase())
        }
        // No -main/-overlay suffix: treat as a standalone main file if it has a date-UUID prefix.
        fallbackRegex.find(fileName)?.let { m ->
            return ParsedName(fileName, m.groupValues[1], m.groupValues[2], "main", m.groupValues[3].lowercase())
        }
        return null
    }

    //META scans ZIP filesystem directly: extracts UUID, date, isVideo, overlay pairing
    //META date comes from filename prefix (YYYY-MM-DD) — no time-of-day here; time must come from hist/json correlation
    //META eliminates HTML parsing complexity and ensures filenames are source of truth
    //META onUnmatched fires for each file in memories/ that doesn't match either regex — used for diagnostics
    fun parseMemoriesFromZip(
        zipFilePath: String,
        onUnmatched: ((filePath: String) -> Unit)? = null
    ): List<HtmlMemoryEntry> =
        parseMemoryEntryNames(listZipEntries(zipFilePath), onUnmatched)

    // Pure filename-list variant of parseMemoriesFromZip — separated from zip I/O for testability.
    fun parseMemoryEntryNames(
        allEntries: List<String>,
        onUnmatched: ((filePath: String) -> Unit)? = null
    ): List<HtmlMemoryEntry> {
        val entries = mutableListOf<HtmlMemoryEntry>()

        val memoriesFiles = allEntries
            .filter { it.startsWith("memories/") && it != "memories/" }
            .filter { !it.endsWith("/") }

        if (memoriesFiles.isEmpty()) return entries

        // Group by full stem (date_uuid), not UUID alone: Snapchat exports contain the same
        // UUID under different dates, and those are distinct memories (see OverlayCombiner,
        // which matches pairs by stem for the same reason).
        val filesByStem = mutableMapOf<String, MutableList<ParsedName>>()

        for (filePath in memoriesFiles) {
            val parsed = parseName(filePath.substringAfterLast('/'))
            if (parsed == null) {
                onUnmatched?.invoke(filePath)
                continue
            }
            filesByStem.getOrPut("${parsed.date}_${parsed.uuid}") { mutableListOf() }.add(parsed)
        }

        for ((_, files) in filesByStem) {
            val mainFile = files.find { it.type == "main" }
            val overlayFile = files.find { it.type == "overlay" }

            if (mainFile != null) {
                entries.add(
                    HtmlMemoryEntry(
                        fileName = mainFile.fileName,
                        uuid = mainFile.uuid,
                        date = mainFile.date,
                        isVideo = mainFile.ext in videoExtensions,
                        hasOverlay = overlayFile != null,
                        overlayFileName = overlayFile?.fileName
                    )
                )
            } else if (overlayFile != null) {
                // Standalone overlay-only memory
                entries.add(
                    HtmlMemoryEntry(
                        fileName = overlayFile.fileName,
                        uuid = overlayFile.uuid,
                        date = overlayFile.date,
                        isVideo = false,
                        hasOverlay = false,
                        overlayFileName = null
                    )
                )
            }
        }

        return entries
    }

}
