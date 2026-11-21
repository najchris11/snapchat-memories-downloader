package com.najdev.snapvault.downloader

private val NUMBERED_EXPORT_ZIP_SUFFIX = Regex("""-(\d+)\.zip$""", RegexOption.IGNORE_CASE)

/**
 * Filters regular file paths down to `.zip` archives and sorts multi-part Snapchat exports
 * in logical part order: the unnumbered base archive (`mydata~1700000000.zip` or
 * `memories.zip`) comes first (`0`), followed by `-1.zip`, `-2.zip`, ..., `-10.zip` in
 * integer order rather than lexicographic order (`-10.zip` before `-2.zip`).
 *
 * Shared across Desktop, Android, and iOS so every target discovers identical archives in
 * identical order.
 */
fun sortExportZipPaths(filePaths: Iterable<String>): List<String> =
    filePaths
        .filter { path -> path.substringAfterLast('/').substringAfterLast('\\').endsWith(".zip", ignoreCase = true) }
        .sortedWith(exportZipPathComparator)

private val exportZipPathComparator: Comparator<String> = Comparator { a, b ->
    val nameA = a.substringAfterLast('/').substringAfterLast('\\')
    val nameB = b.substringAfterLast('/').substringAfterLast('\\')
    val partA = NUMBERED_EXPORT_ZIP_SUFFIX.find(nameA)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val partB = NUMBERED_EXPORT_ZIP_SUFFIX.find(nameB)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val byPart = partA.compareTo(partB)
    if (byPart != 0) byPart else nameA.compareTo(nameB)
}
