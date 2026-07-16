package com.najdev.snapvault

actual fun listZipEntries(zipFilePath: String): List<String> {
    return try {
        java.util.zip.ZipFile(zipFilePath).use { zf ->
            zf.entries().toList().map { it.name }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

actual fun readZipEntryText(zipFilePath: String, entryName: String): String? {
    return try {
        java.util.zip.ZipFile(zipFilePath).use { zf ->
            val entry = zf.getEntry(entryName) ?: return null
            zf.getInputStream(entry).bufferedReader().use { it.readText() }
        }
    } catch (e: Exception) {
        null
    }
}
