package com.najdev.snapvault.downloader

import kotlin.test.Test
import kotlin.test.assertEquals

class ExportZipSorterTest {

    @Test
    fun sortsUnnumberedArchiveBeforeNumberedPartsInNumericOrder() {
        val unsorted = listOf(
            "/exports/mydata~1700000000-10.zip",
            "/exports/mydata~1700000000-2.zip",
            "/exports/mydata~1700000000.zip",
            "/exports/mydata~1700000000-1.zip",
        )

        assertEquals(
            listOf(
                "/exports/mydata~1700000000.zip",
                "/exports/mydata~1700000000-1.zip",
                "/exports/mydata~1700000000-2.zip",
                "/exports/mydata~1700000000-10.zip",
            ),
            sortExportZipPaths(unsorted),
        )
    }

    @Test
    fun acceptsNonMydataExportArchivesAndCaseInsensitiveZipExtension() {
        val mixed = listOf(
            "/exports/notes.txt",
            "/exports/memories-10.ZIP",
            "/exports/memories-2.zip",
            "/exports/.DS_Store",
            "/exports/memories.zip",
        )

        assertEquals(
            listOf(
                "/exports/memories.zip",
                "/exports/memories-2.zip",
                "/exports/memories-10.ZIP",
            ),
            sortExportZipPaths(mixed),
        )
    }
}
