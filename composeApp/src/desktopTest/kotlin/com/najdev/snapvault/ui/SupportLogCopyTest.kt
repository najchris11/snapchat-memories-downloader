package com.najdev.snapvault.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import com.najdev.snapvault.ui.theme.SnapVaultTheme
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

    @Test
    fun copyLogsPutsARedactedLogOnTheClipboard() = runComposeUiTest {
        val viewModel = idleDashboardViewModel()
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
