package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.Screen
import com.najdev.snapvault.ui.components.AppSidebar
import com.najdev.snapvault.ui.components.AppTopBar
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Regression: the top bar and sidebar each drew a 1dp box border on all four sides. On
 * mobile the top bar spans the screen under the status bar, so its top and side strokes
 * framed the bar like a text field, and the sidebar's left/top/bottom strokes boxed the
 * navigation off from the window edge. Each now separates itself with one edge only: the
 * top bar's bottom, the sidebar's right.
 */
@OptIn(ExperimentalTestApi::class)
class ChromeEdgeTest {
    private fun Color.argb() = toString()

    @Test
    fun theTopBarHasOnlyABottomDivider() = runComposeUiTest {
        var outline = Color.Unspecified
        var background = Color.Unspecified
        setContent {
            SnapVaultTheme(darkMode = false) {
                outline = MaterialTheme.colorScheme.outlineVariant
                background = MaterialTheme.colorScheme.background
                Box(Modifier.size(600.dp, 56.dp)) {
                    AppTopBar(showWindowControls = false, onClose = {}, onMinimize = {}, onMaximize = {})
                }
            }
        }
        val pixels = onRoot().captureToImage().toPixelMap()
        val midX = pixels.width / 2
        val midY = pixels.height / 2
        assertEquals(background.argb(), pixels[midX, 0].argb(), "no stroke along the top edge")
        assertEquals(background.argb(), pixels[0, midY].argb(), "no stroke down the left edge")
        assertEquals(background.argb(), pixels[pixels.width - 1, midY].argb(), "no stroke down the right edge")
        assertEquals(outline.argb(), pixels[midX, pixels.height - 1].argb(), "a divider along the bottom edge")
    }

    @Test
    fun theSidebarHasOnlyARightDivider() = runComposeUiTest {
        var outline = Color.Unspecified
        setContent {
            SnapVaultTheme(darkMode = false) {
                outline = MaterialTheme.colorScheme.outlineVariant
                Box(Modifier.width(220.dp).height(400.dp)) {
                    AppSidebar(currentScreen = Screen.Library, isRunning = false, currentStep = 0, onNavigate = {}, supportPageUrl = null)
                }
            }
        }
        val pixels = onRoot().captureToImage().toPixelMap()
        val midY = pixels.height / 2
        assertNotEquals(outline.argb(), pixels[0, midY].argb(), "no stroke down the left edge")
        assertNotEquals(outline.argb(), pixels[pixels.width / 2, 0].argb(), "no stroke along the top edge")
        assertNotEquals(outline.argb(), pixels[pixels.width / 2, pixels.height - 1].argb(), "no stroke along the bottom edge")
        assertEquals(outline.argb(), pixels[pixels.width - 1, midY].argb(), "a divider down the right edge")
    }
}
