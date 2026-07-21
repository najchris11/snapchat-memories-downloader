package com.najdev.snapvault

import okio.FileSystem
import okio.Path.Companion.toPath
import okio.openZip
import okio.use

actual fun listZipEntries(zipFilePath: String): List<String> {
    return try {
        FileSystem.SYSTEM.openZip(zipFilePath.toPath()).use { zipFs ->
            zipFs.listRecursively("/".toPath())
                .map { path -> path.toString().removePrefix("/") }
                .filter { it.isNotEmpty() }
                .toList()
        }
    } catch (e: Exception) {
        emptyList()
    }
}

actual fun readZipEntryText(zipFilePath: String, entryName: String): String? {
    return try {
        FileSystem.SYSTEM.openZip(zipFilePath.toPath()).use { zipFs ->
            val cleanEntry = entryName.removePrefix("/")
            val entryPath = "/$cleanEntry".toPath()
            zipFs.read(entryPath) { readUtf8() }
        }
    } catch (e: Exception) {
        null
    }
}

actual fun listZipEntryTimestamps(zipFilePath: String): Map<String, Long> {
    return try {
        FileSystem.SYSTEM.openZip(zipFilePath.toPath()).use { zipFs ->
            val map = mutableMapOf<String, Long>()
            zipFs.listRecursively("/".toPath()).forEach { path ->
                val relPath = path.toString().removePrefix("/")
                if (relPath.isNotEmpty()) {
                    val metadata = zipFs.metadataOrNull(path)
                    val mtime = metadata?.lastModifiedAtMillis
                    if (mtime != null) {
                        map[relPath] = mtime
                    }
                }
            }
            map
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
