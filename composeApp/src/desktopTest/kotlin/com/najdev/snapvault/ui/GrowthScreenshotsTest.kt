package com.najdev.snapvault.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test

/** Artifact exporter only: generated sample media, never personal exports. */
@OptIn(ExperimentalTestApi::class)
class GrowthScreenshotsTest {
    @Test fun dashboard() = runComposeUiTest {
        val vm = idleDashboardViewModel()
        setContent { Box(Modifier.size(1200.dp, 820.dp)) { SnapVaultTheme(darkMode = true) { Surface { DashboardScreen(vm, {}) } } } }
        waitForIdle()
        File("/tmp/snapvault-dashboard.png").writeBytes(Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        vm.dispose()
    }
    @Test fun library() = runComposeUiTest {
        val folder = File("/tmp/snapvault-growth-demo").apply { mkdirs() }
        repeat(6) { i ->
            val image = BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                paint = GradientPaint(0f, 0f, Color(20 + i * 12, 40, 100), 800f, 600f, Color(120, 50 + i * 20, 120))
                fillRect(0, 0, 800, 600)
                color = Color(230, 180, 100); fillOval(480, 80, 160, 160)
                color = Color(30, 70, 85); fillPolygon(intArrayOf(0, 300, 600), intArrayOf(600, 220, 600), 3)
                color = Color(40, 95, 100); fillPolygon(intArrayOf(250, 600, 800), intArrayOf(600, 300, 600), 3)
                dispose()
            }
            ImageIO.write(image, "jpg", File(folder, "2024-0${i + 1}-12_Demo${i + 1}.jpg"))
        }
        setContent { Box(Modifier.size(1200.dp, 820.dp)) { SnapVaultTheme(darkMode = true) { Surface { LibraryScreen(folder.absolutePath, {}) } } } }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("2024-01-12_Demo1")).fetchSemanticsNodes().isNotEmpty() }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasContentDescription("2024-06-12_Demo6"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
        File("/tmp/snapvault-library.png").writeBytes(Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }
}
