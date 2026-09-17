package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.App
import com.najdev.snapvault.ImportMode
import com.najdev.snapvault.LayoutOverride
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.Screen
import com.najdev.snapvault.ThemeMode
import com.najdev.snapvault.UnenforcedOutputDirectoryLocker
import com.najdev.snapvault.downloader.NoOpZipPipelineRunner
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.platformSupportPageUrl
import com.najdev.snapvault.ui.components.AppSidebar
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import com.najdev.snapvault.viewmodel.DashboardViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SupportLinkTest {
    private val label = "Support me on Ko-fi"
    private val url = "https://ko-fi.com/najdev"

    @Test
    fun desktopSupportDefaultsToTheMaintainersExactPage() {
        assertEquals(url, platformSupportPageUrl)
    }

    @Test
    fun theDefaultOpeningPathHandsTheExactPageToThePlatformBrowserHandler() = runComposeUiTest {
        val opened = AtomicReference<String?>(null)
        setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened.set(uri) }
            }) {
                SnapVaultTheme(darkMode = true) { SupportAction(url) }
            }
        }
        assertEquals(null, opened.get())
        onNode(hasText(label)).performClick()
        waitUntil { opened.get() != null }
        assertEquals(url, opened.get())
    }

    // The maintainer's support page must be reachable from desktop Settings, with clear
    // wording that an optional tip opens an external site rather than unlocking the app.
    @Test
    fun desktopSettingsOffersAnOptionalTipWithAnExternalDestination() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                SettingsScreen(
                    hasExifTool = true,
                    hasFFmpeg = true,
                    onVerifyDependencies = {},
                    downloadFolder = null,
                    onResetIndex = {},
                    onEditOutputPath = {},
                    themeMode = ThemeMode.SYSTEM,
                    onThemeModeChange = {},
                    layoutOverride = LayoutOverride.Auto,
                    onLayoutOverrideChange = {},
                )
            }
        }
        onNode(hasText(label)).performScrollTo().assertExists()
        onNode(hasText("SnapVault is free to use. Optional tips help support development and distribution."))
            .assertExists()
        onNode(hasText("Opens Ko-fi in your browser.")).assertExists()
    }

    @Test
    fun theSidebarButtonSitsBelowStatusAndOpensOnlyOnActivation() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                Box(Modifier.height(700.dp)) {
                    AppSidebar(
                        currentScreen = Screen.Dashboard,
                        isRunning = false,
                        currentStep = 0,
                        onNavigate = {},
                        supportPageUrl = url,
                        onOpenSupportPage = { opened.add(it) },
                    )
                }
            }
        }
        val status = onNode(hasText("Idle")).fetchSemanticsNode().boundsInRoot
        val button = onNode(hasText(label)).fetchSemanticsNode().boundsInRoot
        assertTrue(button.top >= status.bottom, "the support action should be below the status indicator")
        onNode(hasText("Optional tip · Opens your browser")).assertExists()
        assertEquals(emptyList(), opened, "rendering must not open the site")
        onNode(hasText(label)).performClick()
        waitUntil { opened.size == 1 }
        assertEquals(listOf(url), opened)
    }

    @Test
    fun theSupportButtonCanBeActivatedWithTheKeyboard() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                SupportAction(url, onOpenPage = { opened.add(it) })
            }
        }
        onNode(hasText(label)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        onNode(hasText(label)).performKeyInput { pressKey(Key.Enter) }
        waitUntil { opened.size == 1 }
        assertEquals(listOf(url), opened)
    }

    @Test
    fun aBrowserFailureExplainsTheProblemAndOffersTheExactLinkToCopy() = runComposeUiTest {
        val copied = mutableListOf<String>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                SupportAction(
                    url,
                    onOpenPage = { error("no browser available") },
                    onCopyPage = { copied.add(it) },
                )
            }
        }
        onNode(hasText(label)).performClick()
        waitUntil { onAllNodes(hasText("Could not open your browser. Copy the link and open it manually.")).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasText(url)).assertExists()
        onNode(hasText("Copy link")).performClick()
        assertEquals(listOf(url), copied)
        onNode(hasText("Link copied.")).assertExists()
    }

    @Test
    fun anAbsentOrBlankUrlOffersNoDeadSupportControls() {
        for (missing in listOf(null, "", "   ")) {
            runComposeUiTest {
                setContent {
                    SnapVaultTheme(darkMode = true) {
                        SupportSection(missing)
                        SupportAction(missing)
                    }
                }
                onAllNodes(hasText(label)).assertCountEquals(0)
                onAllNodes(hasText("Support SnapVault")).assertCountEquals(0)
            }
        }
    }

    @Test
    fun theButtonWaitsForTheBrowserRequestBeforeAcceptingAnotherActivation() = runComposeUiTest {
        val release = CompletableDeferred<Unit>()
        val opened = mutableListOf<String>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                SupportAction(url, onOpenPage = { opened.add(it); release.await() })
            }
        }
        onNode(hasText(label)).performClick()
        waitUntil { opened.size == 1 }
        onNode(hasText(label)).assertIsNotEnabled()
        assertEquals(listOf(url), opened)
        release.complete(Unit)
        waitForIdle()
        onNode(hasText(label)).performClick()
        waitUntil { opened.size == 2 }
        assertEquals(listOf(url, url), opened)
    }

    @Test
    fun aClipboardFailureLeavesTheAddressVisibleAndDoesNotClaimItWasCopied() = runComposeUiTest {
        setContent {
            SnapVaultTheme(darkMode = true) {
                SupportAction(
                    url,
                    onOpenPage = { error("no browser") },
                    onCopyPage = { error("clipboard unavailable") },
                )
            }
        }
        onNode(hasText(label)).performClick()
        waitUntil { onAllNodes(hasText("Copy link")).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasText("Copy link")).performClick()
        onNode(hasText("Could not copy the link. Select the address above and copy it manually.")).assertExists()
        onNode(hasText(url)).assertExists()
        onAllNodes(hasText("Link copied.")).assertCountEquals(0)
    }

    @Test
    fun openingSupportDuringAnImportLeavesTheRunAndItsDestinationUntouched() = runComposeUiTest {
        val started = CompletableDeferred<Unit>()
        val opened = mutableListOf<String>()
        val runner = object : ZipPipelineRunner by NoOpZipPipelineRunner {
            override suspend fun extractDownloadedArchives(
                outputDir: String,
                archivePaths: List<String>,
                onWarn: (String) -> Unit,
            ): List<String> {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val viewModel = DashboardViewModel(
            zipPipelineRunner = runner,
            mediaProcessor = Tools(),
            fileSystem = FakeFileSystem().apply {
                createDirectories("/out".toPath())
                write("/history.json".toPath()) {
                    writeUtf8("""{"Saved Media": [{"Download Link": "https://example.com/x", "Date": "2024-01-01 00:00:00 UTC"}]}""")
                }
            },
            pickers = object : PlatformPickers {
                override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult("/history.json")
                override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult("/out")
                override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
                override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
            },
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        )
        try {
            viewModel.changeImportMode(ImportMode.Legacy)
            viewModel.pickHtmlFile()
            viewModel.pickOutputFolder()
            viewModel.startSync(false, false, false, false, false, true)
            waitUntil(timeoutMillis = 10_000) { started.isCompleted && viewModel.isRunning }
            setContent {
                SnapVaultTheme(darkMode = true) {
                    SettingsScreen(
                        hasExifTool = true,
                        hasFFmpeg = true,
                        onVerifyDependencies = {},
                        downloadFolder = viewModel.downloadFolder,
                        onResetIndex = {},
                        onEditOutputPath = viewModel::pickOutputFolder,
                        themeMode = ThemeMode.SYSTEM,
                        onThemeModeChange = {},
                        layoutOverride = LayoutOverride.Auto,
                        onLayoutOverrideChange = {},
                        outputFolderChangeable = viewModel.outputFolderChangeable,
                        onOpenSupportPage = { opened.add(it) },
                    )
                }
            }
            onNode(hasText(label)).performScrollTo().performClick()
            waitUntil { opened.size == 1 }
            assertTrue(viewModel.isRunning)
            assertEquals("/out", viewModel.downloadFolder)
            assertEquals(listOf(url), opened)
            onNode(hasText("Edit") and androidx.compose.ui.test.hasClickAction()).assertIsNotEnabled()
        } finally {
            viewModel.dispose()
        }
    }

    private class Tools : MediaProcessor {
        override fun checkExifTool() = true
        override fun checkFFmpeg() = true
        override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = true
        override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = true
        override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = true
    }

    @Test
    fun theSidebarSupportActionRemainsAvailableWhileStatusIsRunning() = runComposeUiTest {
        var navigationCalls = 0
        val opened = mutableListOf<String>()
        setContent {
            SnapVaultTheme(darkMode = true) {
                AppSidebar(
                    currentScreen = Screen.Dashboard,
                    isRunning = true,
                    currentStep = 1,
                    onNavigate = { navigationCalls++ },
                    supportPageUrl = url,
                    onOpenSupportPage = { opened.add(it) },
                )
            }
        }
        onNode(hasText(label)).performClick()
        waitUntil { opened.size == 1 }
        onNode(hasText("Running…")).assertExists()
        assertEquals(0, navigationCalls)
        assertEquals(listOf(url), opened)
    }

    // The compact desktop uses PhoneRoot, so a sidebar-only link disappears on resize.
    @Test
    fun resizingToANarrowDesktopKeepsSupportReachableInSettings() = runComposeUiTest {
        var width by mutableStateOf(1280.dp)
        setContent {
            Box(Modifier.width(width).height(900.dp)) {
                App(
                    pickers = object : PlatformPickers {
                        override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(null)
                        override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(null)
                        override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
                        override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
                    },
                    mediaProcessor = object : MediaProcessor {
                        override fun checkExifTool() = true
                        override fun checkFFmpeg() = true
                        override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = true
                        override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = true
                        override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = true
                    },
                    zipPipelineRunner = NoOpZipPipelineRunner,
                    fileSystem = FakeFileSystem(),
                )
            }
        }
        onNode(hasText(label)).assertExists()
        width = 400.dp
        waitForIdle()
        onNode(hasText("Settings") and androidx.compose.ui.test.hasClickAction()).performClick()
        onNode(hasText(label)).performScrollTo().assertExists()
    }
}
