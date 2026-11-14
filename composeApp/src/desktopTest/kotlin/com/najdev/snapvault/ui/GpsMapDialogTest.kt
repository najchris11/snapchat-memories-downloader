package com.najdev.snapvault.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class GpsMapDialogTest {
    @Test
    fun mappedMemoriesCanBeOpenedFromTheAccessibleList() = runComposeUiTest {
        var selected: LibraryItem? = null
        val memory = LibraryItem("sample", "Jan 01, 2024", "Sample memory", "photo", true, false, latitude = 51.5, longitude = -0.1)
        setContent { SnapVaultTheme(darkMode = true) { GpsMapDialog(listOf(memory), { selected = it }, {}) } }
        onNodeWithText("Offline Memories map").assertExists()
        onNodeWithText("Sample memory", substring = true).performClick()
        assertEquals(memory, selected)
    }
    @Test
    fun oldIndexesWithGpsBadgesDoNotInventMapLocations() = runComposeUiTest {
        val old = LibraryItem("old", "Jan 01, 2024", "Old memory", "photo", true, false)
        setContent { SnapVaultTheme(darkMode = true) { GpsMapDialog(listOf(old), {}, {}) } }
        onNodeWithText("No mapped memories yet. Import again with precise GPS matching to save known coordinates. Missing locations are never guessed.").assertExists()
    }
}
