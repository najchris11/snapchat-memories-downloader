package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.ImportMode
import com.najdev.snapvault.ZipSourceMode
import com.najdev.snapvault.viewmodel.DashboardViewModel
import com.najdev.snapvault.viewmodel.ImportSpaceEstimate
import com.najdev.snapvault.viewmodel.formatBytes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

@Composable
internal fun DiskSpacePlanner(viewModel: DashboardViewModel) {
    val scope = rememberCoroutineScope()
    val sourceKey = listOf(viewModel.importMode, viewModel.zipFolder, viewModel.selectedZipFiles, viewModel.downloadFolder)
    var checking by remember(sourceKey) { mutableStateOf(false) }
    var checked by remember(sourceKey) { mutableStateOf(false) }
    var estimate by remember(sourceKey) { mutableStateOf<ImportSpaceEstimate?>(null) }
    val sourceSelected = when (viewModel.zipSourceMode) {
        ZipSourceMode.Folder -> viewModel.zipFolder != null
        ZipSourceMode.MultipleFiles -> viewModel.selectedZipFiles.isNotEmpty()
    }
    val enabled = !viewModel.isRunning && !checking && viewModel.importMode == ImportMode.Zip &&
        sourceSelected && viewModel.downloadFolder != null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(enabled = enabled, onClick = {
            checking = true
            scope.launch {
                try {
                    estimate = viewModel.estimateImportSpace()
                    checked = true
                } finally { checking = false }
            }
        }) { Text(stringResource(if (checking) Res.string.space_checking else Res.string.space_check)) }
        estimate?.let { value ->
            Text(stringResource(Res.string.space_estimate,
                formatBytes(value.requiredBytes), formatBytes(value.reserveBytes), formatBytes(value.availableBytes)),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(if (value.fits) Res.string.space_fits else Res.string.space_shortfall),
                style = MaterialTheme.typography.labelSmall,
                color = if (value.fits) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        if (checked && estimate == null) {
            Text(stringResource(Res.string.space_unavailable), style = MaterialTheme.typography.bodySmall)
        }
    }
}
