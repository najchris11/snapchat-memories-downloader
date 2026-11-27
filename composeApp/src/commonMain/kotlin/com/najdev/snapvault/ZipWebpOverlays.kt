package com.najdev.snapvault

// Only inspect entries named as PNG overlays. A RIFF container must identify itself as
// WebP before it is imported under a different extension.
internal fun isZipPngOverlay(name: String): Boolean =
    name.startsWith("memories/") && name.endsWith("-overlay.png", ignoreCase = true)

internal fun isWebpHeader(header: ByteArray): Boolean =
    header.size == 12 && header.decodeToString(0, 4) == "RIFF" && header.decodeToString(8, 12) == "WEBP"

internal expect fun listZipWebpOverlays(zipFilePath: String): Set<String>
