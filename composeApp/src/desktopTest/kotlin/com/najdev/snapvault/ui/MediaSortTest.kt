package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The comment read `// Filter + search bar + sorting controls`; there were none, and the grid
 * was hardwired to whatever order `scanMediaFiles` returned.
 *
 * Sorting happens here rather than in the scanner on purpose: changing the order must not
 * re-read the folder, and `scanMediaFiles` keeps its capture-date-over-mtime logic as the
 * default, which `MediaScannerTest` pins and which must not move.
 */
@OptIn(ExperimentalTestApi::class)
class MediaSortTest {

    // In scan order, which is newest first — LibraryItem.date is a formatted display string
    // ("NOV 28, 2024"), so it cannot be sorted on. Newest is therefore the scanner's own
    // order, untouched.
    //
    // The names are chosen so case actually matters: sorted case-sensitively, "Banana" (B is
    // 0x42) comes before "apple" (a is 0x61). A set like Alpha/bravo/charlie would order
    // identically either way and would pass against a case-sensitive sort.
    private val scanned = listOf(
        item(title = "cherry", bytes = 50),
        item(title = "Banana", bytes = 300),
        item(title = "apple", bytes = 100),
    )

    private fun item(title: String, bytes: Long) = LibraryItem(
        id = "/vault/$title.jpg",
        date = "2026-01-01",
        title = title,
        type = "photo",
        hasGps = false,
        hasOverlay = false,
        fileSizeBytes = bytes,
    )

    @Test
    fun newestPreservesTheScannersOwnOrder() {
        assertEquals(
            listOf("cherry", "Banana", "apple"),
            MediaSort.Newest.applyTo(scanned).map { it.title },
        )
    }

    @Test
    fun oldestIsTheScanOrderReversed() {
        assertEquals(
            listOf("apple", "Banana", "cherry"),
            MediaSort.Oldest.applyTo(scanned).map { it.title },
        )
    }

    @Test
    fun largestOrdersBySizeDescending() {
        assertEquals(
            listOf("Banana", "apple", "cherry"),
            MediaSort.Largest.applyTo(scanned).map { it.title },
        )
    }

    // Case-insensitive, or a capital letter jumps an item to the front — which reads as a
    // broken sort rather than as ASCII ordering.
    @Test
    fun nameOrdersAlphabeticallyIgnoringCase() {
        assertEquals(
            listOf("apple", "Banana", "cherry"),
            MediaSort.Name.applyTo(scanned).map { it.title },
        )
    }

    @Test
    fun sortingNeverAddsOrLosesItems() {
        MediaSort.entries.forEach { sort ->
            assertEquals(
                scanned.map { it.title }.toSet(),
                sort.applyTo(scanned).map { it.title }.toSet(),
                "$sort changed which items are present",
            )
        }
    }

    @Test
    fun everySortHasItsOwnLabelFromResources() = runComposeUiTest {
        val labels = mutableMapOf<MediaSort, String>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                MediaSort.entries.forEach { labels[it] = it.label() }
            }
        }

        assertEquals(MediaSort.entries.size, labels.values.toSet().size, "duplicate labels: $labels")
        assertEquals(emptyList(), labels.filterValues { it.isBlank() }.keys.toList())
    }
}
