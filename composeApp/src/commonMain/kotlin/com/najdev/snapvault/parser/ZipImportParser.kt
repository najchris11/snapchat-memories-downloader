package com.najdev.snapvault.parser

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

    private val srcRegex = Regex("""src=["']\.//([^"']+)["']""")
    private val textLineRegex = Regex("""class="text-line">(\d{4}-\d{2}-\d{2})<""")

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
                entries.add(
                    HtmlMemoryEntry(
                        fileName = fileName,
                        uuid = uuid,
                        date = date,
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
}
