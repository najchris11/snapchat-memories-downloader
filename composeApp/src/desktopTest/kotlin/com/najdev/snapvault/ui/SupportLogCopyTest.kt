package com.najdev.snapvault.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.najdev.snapvault.PlatformPickers
import com.najdev.snapvault.UnenforcedOutputDirectoryLocker
import com.najdev.snapvault.downloader.CombineResult
import com.najdev.snapvault.downloader.ExtractResult
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.parser.HtmlMemoryEntry
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import com.najdev.snapvault.viewmodel.DashboardViewModel
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// D18: the redaction has to be what reaches the clipboard, not a helper the copy button
// happens not to call. The on-screen log still shows the raw lines — it is the user's own
// screen — so this goes through the button.
@OptIn(ExperimentalTestApi::class)
class SupportLogCopyTest {

    @Suppress("DEPRECATION")
    private class RecordingClipboard : ClipboardManager {
        var copied: AnnotatedString? = null
        override fun getText(): AnnotatedString? = copied
        override fun setText(annotatedString: AnnotatedString) {
            copied = annotatedString
        }
    }

    private object NoPickers : PlatformPickers {
        override fun pickHtmlFile(onResult: (String?) -> Unit) = onResult(null)
        override fun pickOutputFolder(onResult: (String?) -> Unit) = onResult(null)
        override fun pickZipFolder(onResult: (String?) -> Unit) = onResult(null)
        override fun pickMultipleZips(onResult: (List<String>) -> Unit) = onResult(emptyList())
    }

    private object NoTools : MediaProcessor {
        override fun checkExifTool() = true
        override fun checkFFmpeg() = true
        override fun writeGpsMetadata(filePath: String, latitude: Double, longitude: Double, dateStr: String?) = true
        override fun writeDateMetadata(filePath: String, dateTimeUtc: String) = true
        override fun combineVideoWithOverlay(videoPath: String, overlayPath: String, outputPath: String) = true
    }

    private object NoRunner : ZipPipelineRunner {
        override fun listZipFiles(folderPath: String): List<String> = emptyList()
        override suspend fun extractAll(
            itemsByZip: Map<String, List<HtmlMemoryEntry>>,
            outputDir: String,
            workerCount: Int,
            onProgress: (ExtractResult) -> Unit,
        ) = Unit
        override suspend fun extractDownloadedArchives(
            outputDir: String,
            archivePaths: List<String>,
            onWarn: (String) -> Unit,
        ): List<String> = emptyList()
        override suspend fun combineAll(
            outputDir: String,
            deleteOriginals: Boolean,
            workerCount: Int,
            onStart: (total: Int) -> Unit,
            onMetaStart: (total: Int) -> Unit,
            onMetaError: ((String) -> Unit)?,
            onProgress: (CombineResult) -> Unit,
        ) = Unit
    }

    @Test
    fun copyLogsPutsARedactedLogOnTheClipboard() = runComposeUiTest {
        val viewModel = DashboardViewModel(
            zipPipelineRunner = NoRunner,
            mediaProcessor = NoTools,
            fileSystem = FakeFileSystem(),
            pickers = NoPickers,
            outputDirectoryLocker = UnenforcedOutputDirectoryLocker,
        )
        viewModel.logs += "[ERROR] Failed https://app.snapchat.com/dmd/memories?uid=abc123&sig=S3CR3T"
        viewModel.logs += "[ERROR] Could not write vault index: /Users/jane/out/vault_index.json"
        val clipboard = RecordingClipboard()

        setContent {
            @Suppress("DEPRECATION")
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                SnapVaultTheme(darkMode = true) { DashboardScreen(viewModel = viewModel, onNavigateToSettings = {}) }
            }
        }
        onNodeWithContentDescription("Copy logs").performClick()
        waitForIdle()

        val copied = assertNotNull(clipboard.copied, "nothing was copied").text
        assertFalse("S3CR3T" in copied || "abc123" in copied, "link credentials reached the clipboard: $copied")
        assertFalse("/Users/jane" in copied, "the account name reached the clipboard: $copied")
        // Redacted, not dropped: both lines are still there to diagnose from.
        assertTrue("app.snapchat.com/dmd/memories" in copied && "~/out/vault_index.json" in copied, copied)
        viewModel.dispose()
    }
}
