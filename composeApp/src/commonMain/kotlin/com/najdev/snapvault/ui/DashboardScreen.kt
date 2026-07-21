package com.najdev.snapvault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najdev.snapvault.ImportMode
import com.najdev.snapvault.LocalWindowSize
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.ZipSourceMode
import com.najdev.snapvault.isAndroidBuild
import com.najdev.snapvault.isMobileBuild
import com.najdev.snapvault.ui.theme.ElectricPurple
import com.najdev.snapvault.ui.theme.InfoBlue
import com.najdev.snapvault.ui.theme.SecondaryBlue
import com.najdev.snapvault.ui.theme.TertiaryCyan
import com.najdev.snapvault.ui.theme.SnapVaultColors
import com.najdev.snapvault.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToSettings: () -> Unit,
) {
    var runDownload by remember { mutableStateOf(true) }
    var runMetadata by remember { mutableStateOf(true) }
    var experimentalMetadataMatching by remember { mutableStateOf(true) }
    var runCombine by remember { mutableStateOf(true) }
    var runDedupe by remember { mutableStateOf(true) }
    var dryRun by remember { mutableStateOf(false) }
    var logsExpanded by remember { mutableStateOf(false) }
    var pipelineExpanded by remember { mutableStateOf(false) }
    var logsCopied by remember { mutableStateOf(false) }
    var showLogSheet by remember { mutableStateOf(false) }

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val logListState = rememberLazyListState()
    val logScope = rememberCoroutineScope()
    LaunchedEffect(viewModel.logs.size) {
        if (viewModel.logs.isNotEmpty()) {
            logScope.launch { logListState.animateScrollToItem(viewModel.logs.size - 1) }
        }
    }

    val windowSize = LocalWindowSize.current
    val isCompact = windowSize == WindowSize.Compact

    val canStart = viewModel.downloadFolder != null && when (viewModel.importMode) {
        ImportMode.Zip -> when (viewModel.zipSourceMode) {
            ZipSourceMode.Folder -> viewModel.zipFolder != null
            ZipSourceMode.MultipleFiles -> viewModel.selectedZipFiles.isNotEmpty()
        }
        ImportMode.Legacy -> viewModel.htmlFile != null
    }

    val stepName = when (viewModel.currentStep) {
        0 -> stringResource(Res.string.dash_step_setup)
        1 -> stringResource(Res.string.dash_step_syncing)
        2 -> stringResource(Res.string.dash_step_processing)
        3 -> stringResource(Res.string.dash_step_complete)
        else -> stringResource(Res.string.status_idle)
    }

    if (isCompact) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 76.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status Hero for Compact
                ControlCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Step ${viewModel.currentStep + 1} of 4 — $stepName",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = SnapVaultColors.electricPurple
                            )
                            if (viewModel.logs.isNotEmpty()) {
                                TextButton(onClick = { showLogSheet = true }) {
                                    Text("View Logs (${viewModel.logs.size})", fontSize = 12.sp)
                                }
                            }
                        }

                        Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { viewModel.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxSize(),
                                color = SnapVaultColors.electricPurple,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                strokeWidth = 7.dp
                            )
                            Text(
                                "${(viewModel.progress * 100).toInt()}%",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            viewModel.progressText.ifEmpty { stringResource(Res.string.status_idle) },
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Android / iOS preview banner
                if (isMobileBuild) {
                    PreviewBanner()
                }

                // Controls Card
                ControlCard {
                    SourceDestinationContent(viewModel)
                }

                // Pipeline Options Card
                ControlCard {
                    PipelineOptionsContent(
                        viewModel = viewModel,
                        pipelineExpanded = pipelineExpanded,
                        onExpandedChange = { pipelineExpanded = it },
                        runDownload = runDownload, onDownloadChange = { runDownload = it },
                        runMetadata = runMetadata, onMetadataChange = { runMetadata = it },
                        experimentalMetadataMatching = experimentalMetadataMatching, onExperimentalChange = { experimentalMetadataMatching = it },
                        runCombine = runCombine, onCombineChange = { runCombine = it },
                        runDedupe = runDedupe, onDedupeChange = { runDedupe = it },
                        dryRun = dryRun, onDryRunChange = { dryRun = it }
                    )
                }
            }

            // Sticky Bottom Action Bar
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.startSync(runDownload, runMetadata, experimentalMetadataMatching, runCombine, runDedupe, dryRun) },
                        enabled = !viewModel.isRunning && canStart && (!isMobileBuild || isAndroidBuild),
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SnapVaultColors.electricPurple)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.btn_start_sync), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }

                    if (viewModel.isRunning) {
                        Button(
                            onClick = viewModel::stopSync,
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (showLogSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showLogSheet = false }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Sync Execution Logs", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            TextButton(
                                onClick = {
                                    @Suppress("DEPRECATION")
                                    clipboardManager.setText(AnnotatedString(viewModel.logs.joinToString("\n")))
                                    logsCopied = true
                                    logScope.launch {
                                        kotlinx.coroutines.delay(2000)
                                        logsCopied = false
                                    }
                                }
                            ) {
                                Text(if (logsCopied) "Copied!" else "Copy All")
                            }
                        }

                        LazyColumn(
                            state = logListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .padding(10.dp)
                        ) {
                            items(viewModel.logs) { log ->
                                Text(log, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Desktop / Expanded 2-column layout
        Row(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(
                modifier = Modifier.weight(0.4f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isMobileBuild) PreviewBanner()

                ControlCard {
                    SourceDestinationContent(viewModel)
                }

                ControlCard {
                    PipelineOptionsContent(
                        viewModel = viewModel,
                        pipelineExpanded = pipelineExpanded,
                        onExpandedChange = { pipelineExpanded = it },
                        runDownload = runDownload, onDownloadChange = { runDownload = it },
                        runMetadata = runMetadata, onMetadataChange = { runMetadata = it },
                        experimentalMetadataMatching = experimentalMetadataMatching, onExperimentalChange = { experimentalMetadataMatching = it },
                        runCombine = runCombine, onCombineChange = { runCombine = it },
                        runDedupe = runDedupe, onDedupeChange = { runDedupe = it },
                        dryRun = dryRun, onDryRunChange = { dryRun = it }
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.startSync(runDownload, runMetadata, experimentalMetadataMatching, runCombine, runDedupe, dryRun) },
                        enabled = !viewModel.isRunning && canStart && (!isMobileBuild || isAndroidBuild),
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SnapVaultColors.electricPurple)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.btn_start_sync), fontWeight = FontWeight.Black, fontSize = 15.sp)
                    }

                    Surface(
                        onClick = viewModel::stopSync,
                        enabled = viewModel.isRunning,
                        color = if (viewModel.isRunning) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(52.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = stringResource(Res.string.btn_stop),
                                tint = if (viewModel.isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.weight(0.6f).fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StepItem(1, stringResource(Res.string.dash_step_setup), viewModel.currentStep == 0, viewModel.currentStep > 0, Icons.Outlined.Edit)
                        StepperDivider(viewModel.currentStep > 0)
                        StepItem(2, stringResource(Res.string.dash_step_syncing), viewModel.currentStep == 1, viewModel.currentStep > 1, Icons.Outlined.CloudSync)
                        StepperDivider(viewModel.currentStep > 1)
                        StepItem(3, stringResource(Res.string.dash_step_processing), viewModel.currentStep == 2, viewModel.currentStep > 2, Icons.Outlined.AutoFixHigh)
                        StepperDivider(viewModel.currentStep > 2)
                        StepItem(4, stringResource(Res.string.dash_step_complete), viewModel.currentStep == 3, viewModel.currentStep > 3, Icons.Outlined.TaskAlt)
                    }

                    Spacer(Modifier.weight(1f))

                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { viewModel.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxSize(),
                                color = SnapVaultColors.electricPurple,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                strokeWidth = 9.dp
                            )
                            Text(
                                "${(viewModel.progress * 100).toInt()}%",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        viewModel.progressText.ifEmpty { stringResource(Res.string.status_idle) },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricChip(viewModel.speedText)
                            MetricChip(viewModel.etaText)
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { logsExpanded = !logsExpanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                if (logsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(14.dp)
                            )
                            Text("View Logs", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }

                        if (viewModel.logs.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        @Suppress("DEPRECATION")
                                        clipboardManager.setText(AnnotatedString(viewModel.logs.joinToString("\n")))
                                        logsCopied = true
                                        logScope.launch {
                                            kotlinx.coroutines.delay(2000)
                                            logsCopied = false
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (logsCopied) {
                                    Text("Copied!", fontSize = 10.sp, color = SnapVaultColors.success, fontWeight = FontWeight.SemiBold)
                                } else {
                                    Icon(
                                        Icons.Outlined.ContentCopy,
                                        contentDescription = "Copy logs",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = logsExpanded) {
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .padding(10.dp)
                        ) {
                            items(viewModel.logs) { log ->
                                SelectionContainer {
                                    Text(log, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SnapVaultColors.warning.copy(alpha = 0.12f))
            .border(1.dp, SnapVaultColors.warning.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = SnapVaultColors.warning,
            modifier = Modifier.size(15.dp).padding(top = 1.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (isAndroidBuild) "Android Preview" else "iOS Preview",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = SnapVaultColors.warning
            )
            Text(
                if (isAndroidBuild)
                    "Android Sync Preview: ZIP extraction, deduplication, and image date metadata work. Video overlay combining will land in Phase 2.5."
                else
                    "iOS Preview: UI preview. The sync pipeline and native ZIP reader will land in Phase 3.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SourceDestinationContent(viewModel: DashboardViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionLabel(
            icon = Icons.Outlined.FolderOpen,
            text = stringResource(Res.string.dash_source_title)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ModeToggleButton(
                label = "Legacy (HTML/JSON)",
                selected = viewModel.importMode == ImportMode.Legacy,
                onClick = { viewModel.changeImportMode(ImportMode.Legacy) },
                modifier = Modifier.weight(1f)
            )
            ModeToggleButton(
                label = "ZIP Import",
                selected = viewModel.importMode == ImportMode.Zip,
                onClick = { viewModel.changeImportMode(ImportMode.Zip) },
                modifier = Modifier.weight(1f)
            )
        }

        if (viewModel.importMode == ImportMode.Zip) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModeToggleButton(
                    label = "ZIP Folder",
                    selected = viewModel.zipSourceMode == ZipSourceMode.Folder,
                    onClick = { viewModel.changeZipSourceMode(ZipSourceMode.Folder) },
                    modifier = Modifier.weight(1f)
                )
                ModeToggleButton(
                    label = "Pick Files",
                    selected = viewModel.zipSourceMode == ZipSourceMode.MultipleFiles,
                    onClick = { viewModel.changeZipSourceMode(ZipSourceMode.MultipleFiles) },
                    modifier = Modifier.weight(1f)
                )
            }

            if (viewModel.zipSourceMode == ZipSourceMode.Folder) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("ZIP Export Folder", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilePickerBox(
                        icon = Icons.Outlined.FolderZip,
                        label = viewModel.zipFolder ?: "Select folder containing mydata~*.zip files",
                        onClick = viewModel::pickZipFolder,
                        isSelected = viewModel.zipFolder != null
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("ZIP Files", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (viewModel.selectedZipFiles.isNotEmpty()) {
                            Text(
                                "Clear",
                                fontSize = 11.sp,
                                color = SnapVaultColors.electricPurple,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { viewModel.changeZipSourceMode(ZipSourceMode.MultipleFiles) }
                            )
                        }
                    }
                    FilePickerBox(
                        icon = Icons.Outlined.FolderZip,
                        label = when (viewModel.selectedZipFiles.size) {
                            0 -> "Select mydata~*.zip files…"
                            1 -> viewModel.selectedZipFiles[0].substringAfterLast('/').substringAfterLast('\\')
                            else -> "${viewModel.selectedZipFiles.size} ZIP files selected"
                        },
                        onClick = viewModel::pickMultipleZips,
                        isSelected = viewModel.selectedZipFiles.isNotEmpty()
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(Res.string.dash_history_label),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilePickerBox(
                    icon = Icons.Outlined.FileOpen,
                    label = viewModel.htmlFile ?: stringResource(Res.string.dash_history_placeholder),
                    onClick = viewModel::pickHtmlFile,
                    isSelected = viewModel.htmlFile != null
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(Res.string.dash_output_label),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilePickerBox(
                icon = Icons.Outlined.FolderOpen,
                label = viewModel.downloadFolder ?: stringResource(Res.string.dash_output_placeholder),
                onClick = viewModel::pickOutputFolder,
                isSelected = viewModel.downloadFolder != null
            )
        }
    }
}

@Composable
private fun PipelineOptionsContent(
    viewModel: DashboardViewModel,
    pipelineExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    runDownload: Boolean, onDownloadChange: (Boolean) -> Unit,
    runMetadata: Boolean, onMetadataChange: (Boolean) -> Unit,
    experimentalMetadataMatching: Boolean, onExperimentalChange: (Boolean) -> Unit,
    runCombine: Boolean, onCombineChange: (Boolean) -> Unit,
    runDedupe: Boolean, onDedupeChange: (Boolean) -> Unit,
    dryRun: Boolean, onDryRunChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { onExpandedChange(!pipelineExpanded) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SectionLabel(
                icon = Icons.Outlined.AccountTree,
                text = stringResource(Res.string.dash_pipeline_title)
            )
            Icon(
                if (pipelineExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(visible = pipelineExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (viewModel.importMode == ImportMode.Legacy) {
                    PipelineItem(Icons.Outlined.CloudDownload, stringResource(Res.string.opt_download_memories), runDownload, onDownloadChange)
                }
                val isZipMode = viewModel.importMode != ImportMode.Legacy
                PipelineItem(
                    icon = if (isZipMode) Icons.Outlined.CalendarMonth else Icons.Outlined.GpsFixed,
                    label = if (isZipMode) stringResource(Res.string.opt_write_date_metadata) else stringResource(Res.string.opt_inject_gps),
                    checked = runMetadata,
                    onCheckedChange = onMetadataChange
                )
                AnimatedVisibility(visible = isZipMode && runMetadata) {
                    Box(modifier = Modifier.padding(start = 26.dp)) {
                        PipelineItem(
                            Icons.Outlined.Info,
                            "Precise time + GPS matching (experimental, can be turned off)",
                            experimentalMetadataMatching,
                            onExperimentalChange
                        )
                    }
                }
                PipelineItem(Icons.Outlined.Layers, stringResource(Res.string.opt_combine_overlays), runCombine, onCombineChange)
                PipelineItem(Icons.Outlined.AutoDelete, stringResource(Res.string.opt_clean_duplicates), runDedupe, onDedupeChange)
                AnimatedVisibility(visible = runDedupe) {
                    Box(modifier = Modifier.padding(start = 26.dp)) {
                        PipelineItem(
                            Icons.Outlined.Visibility,
                            stringResource(Res.string.opt_dedupe_dry_run),
                            dryRun,
                            onDryRunChange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(modifier = Modifier.padding(18.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = SnapVaultColors.electricPurple, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ModeToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(34.dp),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) SnapVaultColors.electricPurple.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(
            1.dp,
            if (selected) SnapVaultColors.electricPurple.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) SnapVaultColors.electricPurple else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FilePickerBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    isSelected: Boolean
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(42.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(
            1.dp,
            if (isSelected) SnapVaultColors.electricPurple.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) SnapVaultColors.electricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
            Text(
                label,
                fontSize = 12.sp,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PipelineItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (checked) SnapVaultColors.electricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(15.dp)
        )
        Text(
            label,
            fontSize = 12.sp,
            color = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.7f)
        )
    }
}

@Composable
private fun StepItem(
    stepNumber: Int,
    title: String,
    isActive: Boolean,
    isComplete: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        isComplete -> SnapVaultColors.success.copy(alpha = 0.15f)
                        isActive -> SnapVaultColors.electricPurple.copy(alpha = 0.15f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isComplete) Icons.Default.Check else icon,
                contentDescription = null,
                tint = when {
                    isComplete -> SnapVaultColors.success
                    isActive -> SnapVaultColors.electricPurple
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = if (isActive || isComplete) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive || isComplete) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun StepperDivider(isComplete: Boolean) {
    Box(
        modifier = Modifier
            .width(20.dp)
            .height(2.dp)
            .background(
                if (isComplete) SnapVaultColors.success.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )
    )
}

@Composable
private fun MetricChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
