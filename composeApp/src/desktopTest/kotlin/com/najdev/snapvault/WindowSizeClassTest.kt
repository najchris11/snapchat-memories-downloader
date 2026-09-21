package com.najdev.snapvault

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

// The Medium bucket was computed and then thrown away: App.kt branched only on
// `== Compact`, so every width from 600dp to 840dp got the full desktop layout — a 220dp
// sidebar plus a 280dp inspector plus a grid, inside about 620dp of usable space. These
// pin the bucket boundaries so a future change cannot quietly collapse Medium into
// Expanded again.
class WindowSizeClassTest {

    @Test
    fun autoBucketsByWidthAtTheDocumentedBreakpoints() {
        val auto = LayoutOverride.Auto

        assertEquals(WindowSize.Compact, getActiveWindowSize(0.dp, auto))
        assertEquals(WindowSize.Compact, getActiveWindowSize(599.dp, auto))
        // 600dp is the first Medium width, not the last Compact one.
        assertEquals(WindowSize.Medium, getActiveWindowSize(600.dp, auto))
        assertEquals(WindowSize.Medium, getActiveWindowSize(839.dp, auto))
        assertEquals(WindowSize.Expanded, getActiveWindowSize(840.dp, auto))
        assertEquals(WindowSize.Expanded, getActiveWindowSize(1280.dp, auto))
    }

    @Test
    fun explicitOverrideWinsOverMeasuredWidth() {
        // The Layout setting has to beat the window width in both directions, or the
        // override is decorative.
        assertEquals(WindowSize.Compact, getActiveWindowSize(1600.dp, LayoutOverride.Compact))
        assertEquals(WindowSize.Expanded, getActiveWindowSize(320.dp, LayoutOverride.Expanded))
    }

    @Test
    fun mediumIsDistinctFromBothNeighbours() {
        // Guards the specific regression: Medium must not be equal to Expanded, because the
        // screens use that distinction to decide whether they can afford a second fixed panel.
        val medium = getActiveWindowSize(700.dp, LayoutOverride.Auto)

        assertEquals(WindowSize.Medium, medium)
        kotlin.test.assertNotEquals(WindowSize.Expanded, medium)
        kotlin.test.assertNotEquals(WindowSize.Compact, medium)
    }
}
