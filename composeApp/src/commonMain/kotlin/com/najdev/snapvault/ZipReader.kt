package com.najdev.snapvault

expect fun listZipEntries(zipFilePath: String): List<String>
expect fun readZipEntry(zipFilePath: String, entryName: String): String?
