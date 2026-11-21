package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import com.najdev.snapvault.WindowSize
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regressions found on a real export, on the folded iPhone Duo (466pt wide) and a 412dp
 * Android phone, where several controls were laid out as if the window were a desktop.
 */
private const val DESCRIPTION = "Auto follows the window width. Compact and Expanded force the phone or desktop layout."

/**
 * Regressions found on a real export, on the folded iPhone Duo (466pt wide) and a 412dp
 * Android phone, where several controls were laid out as if the window were a desktop.
 */

@OptIn(ExperimentalTestApi::class)
class NarrowLayoutTest {
    // The Theme and Layout rows put a three-option segmented control beside the label. On a
    // phone the control kept its width and the label column was squeezed to one word per line;
    // on the folded Duo "Layout" broke mid-word ("Layo / ut"). A row too narrow for both now
    // puts the control under the text.
    @Test
    fun aNarrowSettingsRowPutsItsControlUnderTheText() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = false) {
                Box(Modifier.width(340.dp)) {
                    SettingsRow(Icons.Outlined.DarkMode, "Layout", DESCRIPTION) {
                        Text("Auto  Compact  Expanded", Modifier.width(230.dp).testTag("control"))
                    }
                }
            }
        }
        val title = onNodeWithText("Layout").getBoundsInRoot()
        val description = onNodeWithText(DESCRIPTION).getBoundsInRoot()
        val control = onNodeWithTag("control").getBoundsInRoot()
        assertTrue(description.right - description.left > 280.dp, "the text keeps most of the width, got ${description.right - description.left}")
        assertTrue(control.top >= description.bottom, "the control sits under the text: control ${control.top}, text ${description.bottom}")
        assertTrue(title.bottom <= description.top + 1.dp)
    }

    @Test
    fun aWideSettingsRowKeepsItsControlBesideTheText() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = false) {
                Box(Modifier.width(800.dp)) {
                    SettingsRow(Icons.Outlined.DarkMode, "Layout", DESCRIPTION) {
                        Text("Auto  Compact  Expanded", Modifier.width(230.dp).testTag("control"))
                    }
                }
            }
        }
        val description = onNodeWithText(DESCRIPTION).getBoundsInRoot()
        val control = onNodeWithTag("control").getBoundsInRoot()
        assertTrue(control.left >= description.right, "control to the right of the text")
        assertTrue(control.top < description.bottom, "on the same row")
    }

    // The stat chips (memories, photos, videos, with location, size) were one sideways-scrolling
    // row beside the toolbar icons. On a phone, the folded Duo and a 1024px desktop window the
    // last chips were simply cut off, with nothing saying the row scrolls. They now wrap.
    @Test
    fun theLibraryStatChipsWrapInsteadOfRunningOffTheEdge() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = false) {
                Box(Modifier.width(300.dp)) {
                    LibraryStatChips(memories = 555, photos = 304, videos = 251, withLocation = 169, totalSize = "1.9 GB")
                }
            }
        }
        val rootWidth = onRoot().fetchSemanticsNode().size.width
        for (label in listOf("555 Memories", "304 photos", "251 videos", "169 with location", "1.9 GB")) {
            // Unclipped position: clipped bounds always fit, which is how a cut-off chip hid.
            val node = onNodeWithText(label).fetchSemanticsNode()
            val right = node.positionInRoot.x + node.size.width
            assertTrue(right <= rootWidth, "$label must be fully visible, ends at ${right}px of $rootWidth")
        }
    }

    // A preview of a real memory on a 412dp Android phone drew its file size one character
    // per line ("1 / . / 1 / M / B"): the long Snapchat file name took the whole row.
    @Test
    fun aLongFileNameDoesNotCrushThePreviewsFileSize() = runComposeUiTest {
        val item = LibraryItem(
            id = "/vault/x.mp4", date = "AUG 30, 2026",
            title = "2026-08-30_1B2A1751-5CF0-4998-956C-4CCE49896F39-main", type = "video",
            hasGps = true, hasOverlay = false, fileSizeBytes = 1_150_000L,
        )
        setContent { SnapVaultTheme(darkMode = false) { Box(Modifier.width(412.dp)) { PreviewInfoBar(item, onToggleFavorite = {}) } } }
        val size = onNodeWithText("1.1 MB").fetchSemanticsNode().size
        assertTrue(size.width > size.height * 2, "the size reads on one line, got ${size.width}x${size.height}px")
        val title = onNodeWithText(item.title).fetchSemanticsNode()
        assertTrue(title.positionInRoot.x + title.size.width <= onNodeWithText("1.1 MB").fetchSemanticsNode().positionInRoot.x, "the title ends before the size")
    }

    // With two ZIPs picked, the panel said "Detected ZIP archive" over one file's name (a full
    // sandbox path on iOS) and then "2 ZIP files selected", which reads as if one were ignored.
    @Test
    fun severalPickedZipsAreListedByName() = runComposeUiTest {
        val fs = okio.fakefilesystem.FakeFileSystem()
        val paths = listOf("/Users/me/Documents/Imports/mydata~1.zip", "/Users/me/Documents/Imports/mydata~1-28.zip")
        paths.forEach { p -> fs.createDirectories(okio.Path.Companion.run { p.substringBeforeLast('/').toPath() }); fs.write(okio.Path.Companion.run { p.toPath() }) { writeUtf8("zip") } }
        val vm = com.najdev.snapvault.viewmodel.DashboardViewModel(
            IdleRunner, IdleTools, fs,
            object : com.najdev.snapvault.PlatformPickers {
                override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(null)
                override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(null)
                override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
                override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(paths)
            },
            outputDirectoryLocker = com.najdev.snapvault.UnenforcedOutputDirectoryLocker,
            outputFolderMemory = com.najdev.snapvault.OutputFolderMemory.None,
        )
        vm.pickMultipleZips()
        setContent { SnapVaultTheme(darkMode = false) { DashboardScreen(vm, {}, windowSize = WindowSize.Compact, usesDeviceLibrary = true, showsAndroidPreview = false) } }
        onNodeWithText("2 ZIP files selected").assertExists()
        onNodeWithText("mydata~1.zip").assertExists()
        onNodeWithText("mydata~1-28.zip").assertExists()
        onNodeWithText("Detected ZIP archive").assertDoesNotExist()
        onNodeWithText("/Users/me", substring = true).assertDoesNotExist()
        vm.dispose()
    }

    // The two source buttons were a fixed Row: on the folded Duo "Choose export folder" was
    // squeezed into a three-line pill. They wrap onto separate lines instead.
    @Test
    fun theSourceButtonsWrapRatherThanSqueeze() = runComposeUiTest {
        val vm = idleDashboardViewModel()
        setContent { SnapVaultTheme(darkMode = false) { Box(Modifier.width(330.dp)) { DashboardScreen(vm, {}, windowSize = WindowSize.Compact) } } }
        val folder = onNodeWithText("Choose export folder").fetchSemanticsNode().size
        val file = onNodeWithText("Choose export file").fetchSemanticsNode().size
        assertTrue(folder.height <= file.height, "one line, like its neighbour: ${folder.height} vs ${file.height}px")
        vm.dispose()
    }
}
