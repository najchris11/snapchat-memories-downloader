package com.najdev.snapvault

expect fun listZipEntries(zipFilePath: String): List<String>

expect fun readZipEntryText(zipFilePath: String, entryName: String): String?
