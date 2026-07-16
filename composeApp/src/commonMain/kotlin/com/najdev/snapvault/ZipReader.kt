package com.najdev.snapvault

expect fun listZipEntries(zipFilePath: String): List<String>

expect fun readZipEntryText(zipFilePath: String, entryName: String): String?

// Entry name -> capture instant (Unix epoch seconds), read from each ZIP entry's
// Info-ZIP "extended timestamp" extra field (header id 0x5455). Snapchat stamps this
// with the memory's exact capture time, which is otherwise nowhere in the filename —
// it's the only reliable key for correlating a media file to its memories_history.json
// record. Entries without a decodable extra field are omitted.
expect fun listZipEntryTimestamps(zipFilePath: String): Map<String, Long>
