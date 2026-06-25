package com.najdev.snapvault

actual fun listZipEntries(zipFilePath: String): List<String> = emptyList()
actual fun readZipEntry(zipFilePath: String, entryName: String): String? = null
