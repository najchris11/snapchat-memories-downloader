package com.najdev.snapvault

actual fun listZipEntries(zipFilePath: String): List<String> = emptyList()

actual fun readZipEntryText(zipFilePath: String, entryName: String): String? = null

actual fun listZipEntryTimestamps(zipFilePath: String): Map<String, Long> = emptyMap()
