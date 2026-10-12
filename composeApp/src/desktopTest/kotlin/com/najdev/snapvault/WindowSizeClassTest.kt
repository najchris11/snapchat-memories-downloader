package com.najdev.snapvault

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

// The Medium bucket was computed and then thrown away: App.kt branched only on
// `== Compact`, so every width in the Medium range got the full desktop layout — a 220dp
// sidebar plus a 280dp inspector plus a grid, inside the usable space. These
// pin the bucket boundaries so a future change cannot quietly collapse Medium into
// Expanded again.
class WindowSizeClassTest {

    @Test
    fun autoBucketsByWidthAtTheDocumentedBreakpoints() {
        val auto = LayoutOverride.Auto

        assertEquals(WindowSize.Compact, getActiveWindowSize(0.dp, auto))
        assertEquals(WindowSize.Compact, getActiveWindowSize(999.dp, auto))
        // 1000dp is the first Medium width, not the last Compact one.
        assertEquals(WindowSize.Medium, getActiveWindowSize(1000.dp, auto))
        assertEquals(WindowSize.Medium, getActiveWindowSize(1199.dp, auto))
        assertEquals(WindowSize.Expanded, getActiveWindowSize(1200.dp, auto))
        assertEquals(WindowSize.Expanded, getActiveWindowSize(1600.dp, auto))
    }

    // Compact used to end at 600dp. A window filling half of a 14" MacBook Pro is roughly 760dp
    // at the default scaling and 900dp at the most spacious one, so it got the sidebar layout
    // squeezed into half a screen, which read worse than the Compact layout the same window
    // looks fine in. Half a laptop screen now counts as Compact, and the default 1280dp window
    // stays Expanded.
    @Test
    fun halfAScreenOnAFourteenInchLaptopIsCompact() {
        val auto = LayoutOverride.Auto

        assertEquals(WindowSize.Compact, getActiveWindowSize(756.dp, auto))
        assertEquals(WindowSize.Compact, getActiveWindowSize(900.dp, auto))
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
        val medium = getActiveWindowSize(1100.dp, LayoutOverride.Auto)

        assertEquals(WindowSize.Medium, medium)
        kotlin.test.assertNotEquals(WindowSize.Expanded, medium)
        kotlin.test.assertNotEquals(WindowSize.Compact, medium)
    }
}
