package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

@Composable
internal fun SupportSection(
    supportPageUrl: String?,
    onOpenPage: (suspend (String) -> Unit)? = null,
    onCopyPage: ((String) -> Unit)? = null,
) {
    if (supportPageUrl.isNullOrBlank()) return
    SettingsCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsSectionLabel(
                icon = Icons.Outlined.FavoriteBorder,
                text = stringResource(Res.string.support_title),
            )
            Text(
                stringResource(Res.string.support_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(Res.string.support_browser_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SupportAction(supportPageUrl, onOpenPage, onCopyPage, showHint = false)
        }
    }
}

// The sidebar and Settings share the same request/error flow. Rendering this never opens
// a browser; opening a payment page also never implies that a payment was completed.
@Composable
internal fun SupportAction(
    supportPageUrl: String?,
    onOpenPage: (suspend (String) -> Unit)? = null,
    onCopyPage: ((String) -> Unit)? = null,
    showHint: Boolean = true,
) {
    if (supportPageUrl.isNullOrBlank()) return

    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var opening by remember(supportPageUrl) { mutableStateOf(false) }
    var openFailed by remember(supportPageUrl) { mutableStateOf(false) }
    var copied by remember(supportPageUrl) { mutableStateOf(false) }
    var copyFailed by remember(supportPageUrl) { mutableStateOf(false) }
    val accessibleLabel = stringResource(Res.string.support_accessible_label)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Spacer(Modifier.height(2.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = accessibleLabel },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            enabled = !opening,
            onClick = {
                // Guard before launching as well as disabling the button: a rapid second
                // activation can precede the recomposition that shows it disabled.
                if (!opening) {
                    opening = true
                    openFailed = false
                    copied = false
                    copyFailed = false
                    scope.launch {
                        try {
                            if (onOpenPage != null) {
                                onOpenPage(supportPageUrl)
                            } else {
                                withContext(ioDispatcher) { uriHandler.openUri(supportPageUrl) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            openFailed = true
                        } finally {
                            opening = false
                        }
                    }
                }
            },
        ) {
            Text(stringResource(Res.string.support_action), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
        }
        if (showHint) {
            Text(
                stringResource(Res.string.support_sidebar_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (openFailed) {
            Text(
                stringResource(Res.string.support_open_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            SelectionContainer {
                Text(supportPageUrl, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = {
                try {
                    if (onCopyPage != null) onCopyPage(supportPageUrl)
                    else clipboard.setText(AnnotatedString(supportPageUrl))
                    copied = true
                    copyFailed = false
                } catch (_: Exception) {
                    copied = false
                    copyFailed = true
                }
            }) {
                Text(stringResource(Res.string.support_copy_link))
            }
            if (copied || copyFailed) {
                Text(
                    stringResource(if (copied) Res.string.support_copied else Res.string.support_copy_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copyFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
