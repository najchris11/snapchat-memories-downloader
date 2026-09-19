package com.najdev.snapvault.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.Res
import snapchat_memories_downloader.composeapp.generated.resources.close_keep_open
import snapchat_memories_downloader.composeapp.generated.resources.close_quit_anyway
import snapchat_memories_downloader.composeapp.generated.resources.close_unsaved_body
import snapchat_memories_downloader.composeapp.generated.resources.close_unsaved_title

/**
 * Asked when closing ran out of patience with favorites still unsaved.
 *
 * Staying open is the confirm action, not quitting: the favorites are still saving in the
 * background, and the safe choice should be the one a reflexive Enter picks. Dismissing the
 * dialog any other way also keeps the app open, for the same reason.
 */
@Composable
fun UnsavedFavoritesDialog(
    count: Int,
    onKeepOpen: () -> Unit,
    onQuitAnyway: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepOpen,
        title = { Text(stringResource(Res.string.close_unsaved_title)) },
        text = { Text(pluralStringResource(Res.plurals.close_unsaved_body, count, count)) },
        confirmButton = {
            TextButton(onClick = onKeepOpen) { Text(stringResource(Res.string.close_keep_open)) }
        },
        dismissButton = {
            TextButton(onClick = onQuitAnyway) { Text(stringResource(Res.string.close_quit_anyway)) }
        },
    )
}
