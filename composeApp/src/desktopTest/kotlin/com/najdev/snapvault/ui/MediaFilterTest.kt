package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `selectedFilter` was a `String` holding "All", "Photos" or "Videos". The same three literals
 * drove the filter predicate, the tab list, and a `when` mapping them to display resources —
 * so the state key and the label were two representations of one thing, joined only by
 * convention. Renaming a resource would have left the filter silently matching nothing.
 *
 * The predicate could not be tested at all without restating those literals in the test,
 * which would have tested the copy rather than the code.
 */
@OptIn(ExperimentalTestApi::class)
class MediaFilterTest {

    private val library = listOf(
        libraryItem("photo-a", "photo"),
        libraryItem("video-a", "video"),
        libraryItem("photo-b", "photo"),
    )

    private fun libraryItem(title: String, type: String) = LibraryItem(
        id = title,
        date = "2026-01-01",
        title = title,
        type = type,
        hasGps = false,
        hasOverlay = false,
    )

    @Test
    fun eachFilterSelectsItsOwnMediaType() {
        assertEquals(
            listOf("photo-a", "video-a", "photo-b"),
            library.filter(MediaFilter.All::matches).map { it.title },
        )
        assertEquals(
            listOf("photo-a", "photo-b"),
            library.filter(MediaFilter.Photos::matches).map { it.title },
        )
        assertEquals(
            listOf("video-a"),
            library.filter(MediaFilter.Videos::matches).map { it.title },
        )
    }

    // Every filter needs its own label for the same reason every destination does: three tabs
    // reading the same word is not a tab strip.
    @Test
    fun everyFilterHasItsOwnLabelFromResources() = runComposeUiTest {
        val labels = mutableMapOf<MediaFilter, String>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                MediaFilter.entries.forEach { labels[it] = it.label() }
            }
        }

        assertEquals(MediaFilter.entries.size, labels.values.toSet().size, "duplicate labels: $labels")
        assertEquals(emptyList(), labels.filterValues { it.isBlank() }.keys.toList())
    }
}
