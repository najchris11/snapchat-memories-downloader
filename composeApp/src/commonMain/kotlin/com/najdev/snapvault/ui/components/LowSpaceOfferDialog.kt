package com.najdev.snapvault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.Res
import snapchat_memories_downloader.composeapp.generated.resources.lowspace_archives
import snapchat_memories_downloader.composeapp.generated.resources.lowspace_body
import snapchat_memories_downloader.composeapp.generated.resources.lowspace_cancel
import snapchat_memories_downloader.composeapp.generated.resources.lowspace_confirm
import snapchat_memories_downloader.composeapp.generated.resources.lowspace_permanent
import snapchat_memories_downloader.composeapp.generated.resources.lowspace_title

/**
 * The way out of "this import will not fit" (D20).
 *
 * Every number and name the decision rests on is here — what is needed, what is free, what
 * would be freed, and exactly which files would be deleted — because the deletion is permanent
 * and the archives may be the user's only copy of the export. Declining is the dismiss action
 * and the one a stray Escape or click outside lands on; deleting is never the default.
 */
@Composable
fun LowSpaceOfferDialog(
    archiveNames: List<String>,
    requiredText: String,
    availableText: String,
    reclaimableText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.lowspace_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.lowspace_body, requiredText, availableText, reclaimableText))
                Text(
                    pluralStringResource(Res.plurals.lowspace_archives, archiveNames.size, archiveNames.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Named, not counted: a user has to be able to see which files these are.
                Text(archiveNames.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(Res.string.lowspace_permanent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.lowspace_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.lowspace_cancel)) }
        },
    )
}
