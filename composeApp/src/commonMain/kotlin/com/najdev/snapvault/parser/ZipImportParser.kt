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

    // Regexes for deprecated HTML parsing
    private val srcRegex = Regex("""src=["']\.//([^"']+)["']""")
    private val textLineRegex = Regex("""class="text-line">(\d{4}-\d{2}-\d{2})<""")

    //META scans ZIP filesystem directly: extracts UUID, date, isVideo, overlay pairing
    //META date comes from filename prefix (YYYY-MM-DD) — no time-of-day here; time must come from hist/json correlation
    //META eliminates HTML parsing complexity and ensures filenames are source of truth
    //META onUnmatched fires for each file in memories/ that doesn't match nameRegex — used for diagnostics
    fun parseMemoriesFromZip(
        zipFilePath: String,
        onUnmatched: ((filePath: String) -> Unit)? = null
    ): List<HtmlMemoryEntry> {
        val entries = mutableListOf<HtmlMemoryEntry>()

        // List all entries in the memories/ directory of the ZIP
        val allEntries = listZipEntries(zipFilePath)
        val memoriesFiles = allEntries
            .filter { it.startsWith("memories/") && it != "memories/" }
            .filter { !it.endsWith("/") }

        if (memoriesFiles.isEmpty()) return entries

        // Group files by UUID and filter for valid filenames
        val filesByUuid = mutableMapOf<String, MutableList<Pair<String, String>>>() // uuid -> [(fileName, type)]

        for (filePath in memoriesFiles) {
            val fileName = filePath.substringAfterLast('/')
            val m = nameRegex.find(fileName) ?: run { onUnmatched?.invoke(filePath); continue }

            val uuid = m.groupValues[2]
            val type = m.groupValues[3] // "main" or "overlay"

            filesByUuid.getOrPut(uuid) { mutableListOf() }.add(fileName to type)
        }

        // Create entries from the grouped files
        for ((uuid, files) in filesByUuid) {
            val mainFile = files.find { it.second == "main" }
            val overlayFile = files.find { it.second == "overlay" }

            if (mainFile != null) {
                val fileName = mainFile.first
                val m = nameRegex.find(fileName) ?: continue
                val date = m.groupValues[1]
                val ext = m.groupValues[4].lowercase()
                val isVideo = ext in listOf("mp4", "mov", "avi")

                entries.add(
                    HtmlMemoryEntry(
                        fileName = fileName,
                        uuid = uuid,
                        date = date,
                        isVideo = isVideo,
                        hasOverlay = overlayFile != null,
                        overlayFileName = overlayFile?.first
                    )
                )
            } else if (overlayFile != null) {
                // Standalone overlay-only memory
                val fileName = overlayFile.first
                val m = nameRegex.find(fileName) ?: continue
                val date = m.groupValues[1]

                entries.add(
                    HtmlMemoryEntry(
                        fileName = fileName,
                        uuid = uuid,
                        date = date,
                        isVideo = false,
                        hasOverlay = false,
                        overlayFileName = null
                    )
                )
            }
        }

        return entries
    }

    //META deprecated - use parseMemoriesFromZip instead
    @Deprecated("Use parseMemoriesFromZip for filesystem-based parsing instead of HTML parsing")
    fun parseMemoriesHtml(html: String): List<HtmlMemoryEntry> {
        val entries = mutableListOf<HtmlMemoryEntry>()

        // Split on image-container divs (avoids DOT_MATCHES_ALL for multiplatform compat)
        val parts = html.split("""<div class="image-container">""")

        for (rawBlock in parts.drop(1)) {
            // Take up to two closing div pairs to bound the block
            val block = rawBlock.substringBefore("</div>\n</div>").let { s ->
                if (s == rawBlock) rawBlock.substringBefore("</div></div>") else s
            }

            val date = textLineRegex.find(block)?.groupValues?.get(1) ?: continue

            val allSrcs = srcRegex.findAll(block).map { it.groupValues[1] }.toList()
            if (allSrcs.isEmpty()) continue

            // Separate main src from overlay src
            val mainSrc = allSrcs.firstOrNull { src ->
                val m = nameRegex.find(src.substringAfterLast('/'))
                m != null && m.groupValues[3] == "main"
            }
            val overlaySrc = allSrcs.firstOrNull { src ->
                val m = nameRegex.find(src.substringAfterLast('/'))
                m != null && m.groupValues[3] == "overlay"
            }

            if (mainSrc != null) {
                val fileName = mainSrc.substringAfterLast('/')
                val m = nameRegex.find(fileName) ?: continue
                val uuid = m.groupValues[2]
                val ext = m.groupValues[4].lowercase()
                val isVideo = ext in listOf("mp4", "mov", "avi")
                val overlayFileName = overlaySrc?.substringAfterLast('/')
                //META date decision: filename date wins over HTML text-line date (text-line may be save/receive date, not creation date)
                val entryDate = m.groupValues[1].takeIf { it.isNotEmpty() } ?: date
                entries.add(
                    HtmlMemoryEntry(
                        fileName = fileName,
                        uuid = uuid,
                        date = entryDate,
                        isVideo = isVideo,
                        hasOverlay = overlayFileName != null,
                        overlayFileName = overlayFileName
                    )
                )
            } else if (overlaySrc != null) {
                // Standalone overlay-only memory
                val fileName = overlaySrc.substringAfterLast('/')
                val m = nameRegex.find(fileName) ?: continue
                val uuid = m.groupValues[2]
                val entryDate = m.groupValues[1].takeIf { it.isNotEmpty() } ?: date
                entries.add(
                    HtmlMemoryEntry(
                        fileName = fileName,
                        uuid = uuid,
                        date = entryDate,
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
