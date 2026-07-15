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
import com.najdev.snapvault.ZipSourceMode
import com.najdev.snapvault.isAndroidBuild
import com.najdev.snapvault.ui.theme.ElectricPurple
import com.najdev.snapvault.ui.theme.InfoBlue
import com.najdev.snapvault.ui.theme.SecondaryBlue
import com.najdev.snapvault.ui.theme.TertiaryCyan
import com.najdev.snapvault.ui.theme.SnapVaultColors
import com.najdev.snapvault.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToSettings: () -> Unit,
) {
    // These are local UI preferences, not pipeline state
    var runDownload by remember { mutableStateOf(true) }
    var runMetadata by remember { mutableStateOf(true) }
    var runCombine by remember { mutableStateOf(true) }
    var runDedupe by remember { mutableStateOf(true) }
    var dryRun by remember { mutableStateOf(false) }
    var logsExpanded by remember { mutableStateOf(false) }
    var pipelineExpanded by remember { mutableStateOf(false) }
    var logsCopied by remember { mutableStateOf(false) }

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val logListState = rememberLazyListState()
    val logScope = rememberCoroutineScope()
    LaunchedEffect(viewModel.logs.size) {
        if (viewModel.logs.isNotEmpty()) {
            logScope.launch { logListState.animateScrollToItem(viewModel.logs.size - 1) }
        }
    }

    Row(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // ── Left column: controls (40%) ──────────────────────────────────────
        Column(
            modifier = Modifier.weight(0.4f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Android preview banner
            if (isAndroidBuild) {
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
                            "Android Preview",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = SnapVaultColors.warning
                        )
                        Text(
                            "Date metadata write and video overlay combining are not yet implemented on Android. ZIP extraction and GPS tagging for images work.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Source & Destination card
            ControlCard {
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
                                if (viewModel.selectedZipFiles.size > 1) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        viewModel.selectedZipFiles.take(4).forEach { path ->
                                            Text(
                                                path.substringAfterLast('/').substringAfterLast('\\'),
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (viewModel.selectedZipFiles.size > 4) {
                                            Text(
                                                "+ ${viewModel.selectedZipFiles.size - 4} more",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                    }
                                }
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

            // Pipeline options card
            ControlCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { pipelineExpanded = !pipelineExpanded },
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
                                PipelineItem(Icons.Outlined.CloudDownload, stringResource(Res.string.opt_download_memories), runDownload) { runDownload = it }
                            }
                            val isZipMode = viewModel.importMode != ImportMode.Legacy
                            PipelineItem(
                                icon = if (isZipMode) Icons.Outlined.CalendarMonth else Icons.Outlined.GpsFixed,
                                label = if (isZipMode) stringResource(Res.string.opt_write_date_metadata) else stringResource(Res.string.opt_inject_gps),
                                checked = runMetadata,
                                onCheckedChange = { runMetadata = it }
                            )
                            PipelineItem(Icons.Outlined.Layers, stringResource(Res.string.opt_combine_overlays), runCombine) { runCombine = it }
                            PipelineItem(Icons.Outlined.AutoDelete, stringResource(Res.string.opt_clean_duplicates), runDedupe) { runDedupe = it }
                            // Dedupe deletes files — give it a preview mode.
                            AnimatedVisibility(visible = runDedupe) {
                                Box(modifier = Modifier.padding(start = 26.dp)) {
                                    PipelineItem(
                                        Icons.Outlined.Visibility,
                                        stringResource(Res.string.opt_dedupe_dry_run),
                                        dryRun
                                    ) { dryRun = it }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Action buttons
            val canStart = viewModel.downloadFolder != null && when (viewModel.importMode) {
                ImportMode.Zip -> when (viewModel.zipSourceMode) {
                    ZipSourceMode.Folder -> viewModel.zipFolder != null
                    ZipSourceMode.MultipleFiles -> viewModel.selectedZipFiles.isNotEmpty()
                }
                ImportMode.Legacy -> viewModel.htmlFile != null
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { viewModel.startSync(runDownload, runMetadata, runCombine, runDedupe, dryRun) },
                    enabled = !viewModel.isRunning && canStart,
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

        // ── Right column: status-first layout (60%) ──────────────────────────
        Surface(
            modifier = Modifier.weight(0.6f).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp)
            ) {
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
                        if (viewModel.logs.isNotEmpty() && !logsExpanded) {
                            Text(
                                viewModel.logs.last(),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (viewModel.logs.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    @Suppress("DEPRECATION")
                                    clipboardManager.setText(
                                        AnnotatedString(viewModel.logs.joinToString("\n"))
                                    )
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
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = logsExpanded) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp),
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        SelectionContainer {
                            LazyColumn(
                                state = logListState,
                                modifier = Modifier.fillMaxSize().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                items(viewModel.logs) { log -> TerminalLogLine(log) }
                                if (viewModel.isRunning) {
                                    item {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("$ ", color = ElectricPurple, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                            Text("running_pipeline", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                            BlinkingCursor()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Shared sub-components ────────────────────────────────────────────────────

@Composable
private fun MetricChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SectionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
        Text(text = text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 0.8.sp)
    }
}

@Composable
fun ControlCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(modifier = Modifier.padding(20.dp)) { content() }
    }
}

@Composable
fun FilePickerBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    isSelected: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(15.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(stringResource(Res.string.browse_btn), fontSize = 10.sp, color = SnapVaultColors.electricPurple, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PipelineItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = if (checked) SnapVaultColors.electricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SnapVaultColors.electricPurple,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
fun TerminalDot(color: Color) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(100)).background(color.copy(alpha = 0.6f)))
}

@Composable
fun BlinkingCursor() {
    val alpha by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blink"
    )
    Box(Modifier.padding(start = 3.dp).width(7.dp).height(14.dp).alpha(alpha).background(SnapVaultColors.electricPurple))
}

@Composable
fun TerminalLogLine(log: String) {
    val (tag, content) = when {
        log.contains("[INFO]") -> "INFO" to log.replace("[INFO]", "").trim()
        log.contains("[SUCCESS]") -> "SUCCESS" to log.replace("[SUCCESS]", "").trim()
        log.contains("[ERROR]") -> "ERROR" to log.replace("[ERROR]", "").trim()
        log.contains("[WARN]") -> "WARN" to log.replace("[WARN]", "").trim()
        log.contains("[DOWNLOADED]") -> "DL" to log.replace("[DOWNLOADED]", "").trim()
        log.contains("[SKIPPED]") -> "SKIP" to log.replace("[SKIPPED]", "").trim()
        log.contains("[DELETED DUPES]") -> "DEDUPE" to log.replace("[DELETED DUPES]", "").trim()
        log.contains("[METADATA]") -> "META" to log.replace("[METADATA]", "").trim()
        else -> "" to log
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tag.isNotEmpty()) {
            Text(
                text = "[$tag]",
                color = when (tag) {
                    "SUCCESS", "DL", "DEDUPE" -> SnapVaultColors.success
                    "ERROR" -> SnapVaultColors.error
                    "WARN" -> SnapVaultColors.warning
                    "META" -> SnapVaultColors.info
                    "SKIP" -> SecondaryBlue.copy(alpha = 0.85f)
                    else -> SnapVaultColors.info
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(text = content, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
fun StepItem(
    step: Int,
    label: String,
    active: Boolean,
    complete: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(100))
                .background(when { complete -> SnapVaultColors.electricPurple; active -> SnapVaultColors.electricPurple.copy(alpha = 0.2f); else -> MaterialTheme.colorScheme.surfaceContainerLowest })
                .border(1.5.dp, if (active || complete) SnapVaultColors.electricPurple else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(100)),
            contentAlignment = Alignment.Center
        ) {
            when {
                complete -> Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                active -> Icon(icon, null, tint = SnapVaultColors.electricPurple, modifier = Modifier.size(15.dp))
                else -> Text(step.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active || complete) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun StepperDivider(filled: Boolean = false) {
    Box(Modifier.width(36.dp).height(1.5.dp).background(if (filled) SnapVaultColors.electricPurple.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun ModeToggleButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) SnapVaultColors.electricPurple.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, if (selected) SnapVaultColors.electricPurple.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) SnapVaultColors.electricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
    }
}
