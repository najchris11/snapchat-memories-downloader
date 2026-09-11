package com.najdev.snapvault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.ZipSourceMode
import com.najdev.snapvault.binaryInstallHint
import com.najdev.snapvault.isAndroidBuild
import com.najdev.snapvault.ui.theme.LogColors
import com.najdev.snapvault.ui.theme.SnapVaultColors
import com.najdev.snapvault.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

// Pipeline option defaults, named rather than inlined so they can be asserted. The N1
// blocker was a combination of three of these — a destructive step enabled, its preview
// off, and the whole card collapsed — and nothing would have caught a silent flip back.
internal const val DEFAULT_RUN_DOWNLOAD = true
internal const val DEFAULT_RUN_METADATA = true
internal const val DEFAULT_PRECISE_MATCHING = true
internal const val DEFAULT_RUN_COMBINE = true
internal const val DEFAULT_RUN_DEDUPE = true

// Deletion is an explicit opt-out: preview on, and the card open so the enabled steps are
// visible before Start is pressed.
internal const val DEFAULT_DRY_RUN = true
internal const val DEFAULT_PIPELINE_EXPANDED = true

internal fun usesCompactDashboardLayout(windowSize: WindowSize): Boolean =
    windowSize != WindowSize.Expanded

// Both steppers count to this. It was a literal 4 in the compact layout and four hand-written
// call sites in the expanded one, which is how they were free to disagree.
internal const val DASHBOARD_STEP_COUNT = 4

// Option state lives in one holder rather than seven loose `var`s, so the controls, the
// action row and the status panel can be separate composables that the two layouts compose
// in a different order.
internal class PipelineOptions {
    var runDownload by mutableStateOf(DEFAULT_RUN_DOWNLOAD)
    var runMetadata by mutableStateOf(DEFAULT_RUN_METADATA)
    var preciseMatching by mutableStateOf(DEFAULT_PRECISE_MATCHING)
    var runCombine by mutableStateOf(DEFAULT_RUN_COMBINE)
    var runDedupe by mutableStateOf(DEFAULT_RUN_DEDUPE)
    var dryRun by mutableStateOf(DEFAULT_DRY_RUN)
    var expanded by mutableStateOf(DEFAULT_PIPELINE_EXPANDED)
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToSettings: () -> Unit,
    hasExifTool: Boolean = true,
    hasFFmpeg: Boolean = true,
    windowSize: WindowSize = WindowSize.Expanded,
) {
    val options = remember { PipelineOptions() }

    if (usesCompactDashboardLayout(windowSize)) {
        // One scrolling column. Compact cannot fit the two panels, and Medium retains the
        // 220dp sidebar, leaving too little content width for the four-circle stepper.
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DashboardControls(viewModel, options, onNavigateToSettings, hasExifTool, hasFFmpeg)
                DashboardStatus(viewModel, compact = true)
            }
            Spacer(Modifier.height(16.dp))
            DashboardActions(viewModel, options)
        }
    } else {
        Row(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Controls left, status right. The cards scroll; the action row below stays
            // pinned, so Start stays reachable however tall the cards get.
            Column(modifier = Modifier.weight(0.4f)) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DashboardControls(viewModel, options, onNavigateToSettings, hasExifTool, hasFFmpeg)
                }
                Spacer(Modifier.height(16.dp))
                DashboardActions(viewModel, options)
            }
            DashboardStatus(
                viewModel = viewModel,
                compact = false,
                modifier = Modifier.weight(0.6f).fillMaxHeight(),
            )
        }
    }
}

// ── Controls ─────────────────────────────────────────────────────────────────

@Composable
private fun DashboardControls(
    viewModel: DashboardViewModel,
    options: PipelineOptions,
    onNavigateToSettings: () -> Unit,
    hasExifTool: Boolean,
    hasFFmpeg: Boolean,
) {
    // Android preview banner
    if (isAndroidBuild) {
        InlineBanner(
            icon = Icons.Outlined.Info,
            accent = SnapVaultColors.warning,
            title = stringResource(Res.string.banner_android_preview_title),
            body = stringResource(Res.string.banner_android_preview_body),
        )
    }

    // A missing binary means a pipeline step silently does nothing, and the only
    // place that said so — along with the install instructions — was Settings,
    // which the Dashboard had no route to. Gated on binaryInstallHint() being
    // non-empty, the same condition Settings uses, so this stays off on Android
    // and iOS where neither tool applies and the banner above covers the gap.
    val missingDeps = buildList {
        if (!hasExifTool) add(stringResource(Res.string.banner_deps_exiftool))
        if (!hasFFmpeg) add(stringResource(Res.string.banner_deps_ffmpeg))
    }
    if (missingDeps.isNotEmpty() && binaryInstallHint().isNotEmpty()) {
        InlineBanner(
            icon = Icons.Outlined.ErrorOutline,
            accent = SnapVaultColors.warning,
            title = stringResource(Res.string.banner_deps_title),
            body = missingDeps.joinToString(" "),
            actionLabel = stringResource(Res.string.btn_open_settings),
            onAction = onNavigateToSettings,
        )
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
                // ZIP first: it's the recommended path and the only mode that reads the
                // raw export archives directly.
                ModeToggleButton(
                    label = stringResource(Res.string.opt_mode_zip),
                    selected = viewModel.importMode == ImportMode.Zip,
                    onClick = { viewModel.changeImportMode(ImportMode.Zip) },
                    modifier = Modifier.weight(1f),
                    enabled = !viewModel.isRunning
                )
                ModeToggleButton(
                    label = stringResource(Res.string.opt_mode_legacy),
                    selected = viewModel.importMode == ImportMode.Legacy,
                    onClick = { viewModel.changeImportMode(ImportMode.Legacy) },
                    modifier = Modifier.weight(1f),
                    enabled = !viewModel.isRunning
                )
            }

            if (viewModel.importMode == ImportMode.Zip) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ModeToggleButton(
                        label = stringResource(Res.string.zip_source_folder),
                        selected = viewModel.zipSourceMode == ZipSourceMode.Folder,
                        onClick = { viewModel.changeZipSourceMode(ZipSourceMode.Folder) },
                        modifier = Modifier.weight(1f),
                        enabled = !viewModel.isRunning
                    )
                    ModeToggleButton(
                        label = stringResource(Res.string.zip_source_files),
                        selected = viewModel.zipSourceMode == ZipSourceMode.MultipleFiles,
                        onClick = { viewModel.changeZipSourceMode(ZipSourceMode.MultipleFiles) },
                        modifier = Modifier.weight(1f),
                        enabled = !viewModel.isRunning
                    )
                }

                if (viewModel.zipSourceMode == ZipSourceMode.Folder) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(Res.string.zip_folder_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilePickerBox(
                            icon = Icons.Outlined.FolderZip,
                            label = viewModel.zipFolder ?: stringResource(Res.string.zip_folder_placeholder),
                            onClick = viewModel::pickZipFolder,
                            isSelected = viewModel.zipFolder != null,
                            enabled = !viewModel.isRunning
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(Res.string.zip_files_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (viewModel.selectedZipFiles.isNotEmpty()) {
                                TextButton(
                                    onClick = { viewModel.changeZipSourceMode(ZipSourceMode.MultipleFiles) },
                                    enabled = !viewModel.isRunning,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        stringResource(Res.string.btn_clear),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
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
                            isSelected = viewModel.selectedZipFiles.isNotEmpty(),
                            enabled = !viewModel.isRunning
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
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (viewModel.selectedZipFiles.size > 4) {
                                    Text(
                                        "+ ${viewModel.selectedZipFiles.size - 4} more",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilePickerBox(
                        icon = Icons.Outlined.FileOpen,
                        label = viewModel.htmlFile ?: stringResource(Res.string.dash_history_placeholder),
                        onClick = viewModel::pickHtmlFile,
                        isSelected = viewModel.htmlFile != null,
                        enabled = !viewModel.isRunning
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(Res.string.dash_output_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilePickerBox(
                    icon = Icons.Outlined.FolderOpen,
                    label = viewModel.downloadFolder ?: stringResource(Res.string.dash_output_placeholder),
                    onClick = viewModel::pickOutputFolder,
                    isSelected = viewModel.downloadFolder != null,
                    enabled = !viewModel.isRunning
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
                    .clickable(role = Role.Button) { options.expanded = !options.expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SectionLabel(
                    icon = Icons.Outlined.AccountTree,
                    text = stringResource(Res.string.dash_pipeline_title)
                )
                Icon(
                    if (options.expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = options.expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (viewModel.importMode == ImportMode.Legacy) {
                        PipelineItem(Icons.Outlined.CloudDownload, stringResource(Res.string.opt_download_memories), options.runDownload) { options.runDownload = it }
                    }
                    val isZipMode = viewModel.importMode != ImportMode.Legacy
                    PipelineItem(
                        icon = if (isZipMode) Icons.Outlined.CalendarMonth else Icons.Outlined.GpsFixed,
                        label = if (isZipMode) stringResource(Res.string.opt_write_date_metadata) else stringResource(Res.string.opt_inject_gps),
                        checked = options.runMetadata,
                        onCheckedChange = { options.runMetadata = it }
                    )
                    AnimatedVisibility(visible = isZipMode && options.runMetadata) {
                        PipelineItem(
                            Icons.Outlined.Info,
                            stringResource(Res.string.opt_precise_matching),
                            options.preciseMatching
                        ) { options.preciseMatching = it }
                    }
                    PipelineItem(Icons.Outlined.Layers, stringResource(Res.string.opt_combine_overlays), options.runCombine) { options.runCombine = it }
                    PipelineItem(Icons.Outlined.AutoDelete, stringResource(Res.string.opt_clean_duplicates), options.runDedupe) { options.runDedupe = it }
                    // Dedupe deletes files — give it a preview mode.
                    AnimatedVisibility(visible = options.runDedupe) {
                        PipelineItem(
                            Icons.Outlined.Visibility,
                            stringResource(Res.string.opt_dedupe_dry_run),
                            options.dryRun
                        ) { options.dryRun = it }
                    }
                }
            }
        }
    }
}

// ── Action row ───────────────────────────────────────────────────────────────

@Composable
private fun DashboardActions(
    viewModel: DashboardViewModel,
    options: PipelineOptions,
) {
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
            onClick = { viewModel.startSync(options.runDownload, options.runMetadata, options.preciseMatching, options.runCombine, options.runDedupe, options.dryRun) },
            enabled = !viewModel.isRunning && canStart,
            modifier = Modifier.weight(1f).height(52.dp),
            shape = RoundedCornerShape(10.dp),
            // No colour override: primary is the brand violet, so the default container is
            // already right and contentColor resolves to onPrimary rather than to whatever
            // LocalContentColor happens to be.
        ) {
            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.btn_start_sync), fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
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

// ── Status panel ─────────────────────────────────────────────────────────────

@Composable
private fun DashboardStatus(
    viewModel: DashboardViewModel,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    var logsExpanded by remember { mutableStateOf(false) }
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

    Surface(
        modifier = if (compact) modifier.fillMaxWidth() else modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = (if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                .padding(20.dp)
        ) {
    // The four-circle stepper needs roughly 270dp (4 x 30dp circles, 3 x 36dp dividers, plus
    // labels). Below that it squashes, so compact gets a single-line equivalent instead.
    if (compact) {
        CompactStepper(currentStep = viewModel.currentStep, hasWarnings = viewModel.hasWarnings)
    } else {
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
            StepItem(4, stringResource(Res.string.dash_step_complete), viewModel.currentStep == 3, viewModel.currentStep > 3, Icons.Outlined.TaskAlt, warning = viewModel.hasWarnings)
        }
    }

    // A weight() spacer needs a bounded height. In the compact layout this panel sits inside
    // a verticalScroll, where height is infinite, so it gets fixed spacing instead.
    if (compact) Spacer(Modifier.height(20.dp)) else Spacer(Modifier.weight(1f))

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        PipelineProgressRing(
            progress = viewModel.progress,
            indeterminate = viewModel.indeterminate,
        )
    }

    Spacer(Modifier.height(14.dp))

    Text(
        viewModel.progressText.ifEmpty { stringResource(Res.string.status_idle) },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )

    // A run can finish having reported failures. Without this the only signal is
    // step 4's circle turning amber, which reads as a slightly different success.
    AnimatedVisibility(visible = viewModel.hasWarnings) {
        Box(modifier = Modifier.padding(top = 12.dp)) {
            InlineBanner(
                icon = Icons.Outlined.WarningAmber,
                accent = SnapVaultColors.warning,
                title = stringResource(Res.string.warn_run_failures_title, viewModel.failureCount),
                body = stringResource(Res.string.warn_run_failures_body),
                actionLabel = stringResource(Res.string.btn_view_log),
                onAction = { logsExpanded = true },
            )
        }
    }

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

    if (compact) Spacer(Modifier.height(16.dp)) else Spacer(Modifier.weight(1f))

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                .clickable(role = Role.Button) { logsExpanded = !logsExpanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                if (logsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Text(stringResource(Res.string.log_view_logs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (viewModel.logs.isNotEmpty() && !logsExpanded) {
                Text(
                    viewModel.logs.last(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (viewModel.logs.isNotEmpty()) {
            IconButton(
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(
                        AnnotatedString(viewModel.logs.joinToString("\n"))
                    )
                    logsCopied = true
                    logScope.launch {
                        kotlinx.coroutines.delay(2000)
                        logsCopied = false
                    }
                },
                modifier = Modifier.size(32.dp),
            ) {
                if (logsCopied) {
                    Text(
                        stringResource(Res.string.status_copied),
                        style = MaterialTheme.typography.labelSmall,
                        color = SnapVaultColors.success,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(Res.string.btn_copy_logs),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }

    AnimatedVisibility(visible = logsExpanded) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp),
            color = LogColors.surface,
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
                                Text("$ ", color = LogColors.prompt, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                Text("running_pipeline", color = LogColors.onSurface, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
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

// ── Shared sub-components ────────────────────────────────────────────────────

/**
 * Tinted advisory strip used for every inline Dashboard notice — the Android feature-gap
 * banner, a missing-dependency warning, and a finished run that reported failures — so all
 * three read as the same kind of message rather than three bespoke layouts.
 */
@Composable
fun InlineBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(15.dp).padding(top = 1.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = accent)
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = accent)
            }
        }
    }
}

@Composable
private fun MetricChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SectionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 0.8.sp)
    }
}

@Composable
fun ControlCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
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
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f * contentAlpha),
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f * contentAlpha)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f * contentAlpha)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            stringResource(Res.string.browse_btn),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
            fontWeight = FontWeight.Bold
        )
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
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .minimumInteractiveComponentSize()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Switch(
            checked = checked,
            // Null: the row above owns both the interaction and the semantics, so the
            // Switch must not announce itself as a second control for the same option.
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
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
    Box(Modifier.padding(start = 3.dp).width(7.dp).height(14.dp).alpha(alpha).background(MaterialTheme.colorScheme.primary))
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
                    "SUCCESS", "DL", "DEDUPE" -> LogColors.success
                    "ERROR" -> LogColors.error
                    "WARN" -> LogColors.warning
                    "META" -> LogColors.info
                    "SKIP" -> LogColors.muted
                    else -> LogColors.info
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Text(text = content, color = LogColors.onSurface, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The overall-progress ring, extracted from the status panel so its semantics can be
 * asserted. Material's indicators publish a [androidx.compose.ui.semantics.ProgressBarRangeInfo]
 * of their own; what they cannot supply is what the bar is measuring, so the label is set here.
 */
@Composable
internal fun PipelineProgressRing(
    progress: Float,
    indeterminate: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Res.string.dash_progress_ring)
    Box(modifier = modifier.size(110.dp), contentAlignment = Alignment.Center) {
        if (indeterminate) {
            // Real work is happening but has no per-item signal to report (post-combine date
            // fallback, dedupe scanning) — an animated indeterminate ring, not a percentage
            // that would otherwise sit at a misleadingly precise 0%.
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize().semantics { contentDescription = label },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 9.dp
            )
        } else {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = label },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 9.dp
            )
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * The sentence a screen reader gets for one step — "Step 2 of 4, in progress".
 *
 * Shared by both steppers so they cannot describe the same state differently. The expanded
 * stepper draws this state as fill and border and nothing else, which is why it needs a state
 * description at all.
 */
@Composable
internal fun stepStateDescription(
    step: Int,
    active: Boolean,
    complete: Boolean,
    warning: Boolean = false,
): String {
    val status = when {
        complete && warning -> stringResource(Res.string.dash_step_state_warnings)
        complete -> stringResource(Res.string.dash_step_state_complete)
        active -> stringResource(Res.string.dash_step_state_active)
        else -> stringResource(Res.string.dash_step_state_pending)
    }
    return stringResource(Res.string.dash_step_state, step, DASHBOARD_STEP_COUNT, status)
}

@Composable
fun StepItem(
    step: Int,
    label: String,
    active: Boolean,
    complete: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    warning: Boolean = false,
) {
    val state = stepStateDescription(step, active, complete, warning)
    // A run can reach the terminal step while reporting failures (BUG-15/BUG-01-class
    // issues) — that must not render identically to a clean success.
    val accentColor = if (complete && warning) SnapVaultColors.warning else MaterialTheme.colorScheme.primary
    // The check sits on that fill, so its colour has to follow it: white was unreadable on
    // the amber warning state and only marginal on the violet.
    val onAccentColor = if (complete && warning) SnapVaultColors.onWarning else MaterialTheme.colorScheme.onPrimary
    Column(
        // Merged so the circle and the label read as one item rather than a shape followed
        // by a word — the number, icon and fill carry no text of their own.
        modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = state },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(100))
                .background(when { complete -> accentColor; active -> accentColor.copy(alpha = 0.2f); else -> MaterialTheme.colorScheme.surfaceContainerLowest })
                .border(1.5.dp, if (active || complete) accentColor else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(100)),
            contentAlignment = Alignment.Center
        ) {
            when {
                complete -> Icon(Icons.Default.Check, null, tint = onAccentColor, modifier = Modifier.size(15.dp))
                active -> Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                else -> Text(step.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active || complete) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Single-line equivalent of the four-circle stepper, for windows too narrow to fit it.
 * Carries the same three facts — which step, how many, and whether the finished run
 * reported failures — as text plus dots, in a row that cannot squash.
 */
@Composable
internal fun CompactStepper(currentStep: Int, hasWarnings: Boolean) {
    val labels = listOf(
        stringResource(Res.string.dash_step_setup),
        stringResource(Res.string.dash_step_syncing),
        stringResource(Res.string.dash_step_processing),
        stringResource(Res.string.dash_step_complete),
    )
    val index = currentStep.coerceIn(0, labels.lastIndex)
    val accent = if (currentStep >= labels.lastIndex && hasWarnings) {
        SnapVaultColors.warning
    } else {
        MaterialTheme.colorScheme.primary
    }

    // Same state, same sentence as the expanded stepper — the dots are decoration and carry
    // no semantics of their own.
    val state = stepStateDescription(
        step = index + 1,
        active = currentStep == index,
        complete = currentStep > index,
        warning = hasWarnings,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            labels.indices.forEach { i ->
                Box(
                    Modifier
                        .size(if (i == index) 8.dp else 6.dp)
                        .clip(RoundedCornerShape(100))
                        .background(
                            if (i <= currentStep) accent else MaterialTheme.colorScheme.outlineVariant
                        )
                )
            }
        }
        Text(
            text = stringResource(Res.string.dash_step_progress, index + 1, DASHBOARD_STEP_COUNT, labels[index]),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun StepperDivider(filled: Boolean = false) {
    Box(Modifier.width(36.dp).height(1.5.dp).background(if (filled) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun ModeToggleButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.5f
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(32.dp),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f * contentAlpha) else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f * contentAlpha) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f * contentAlpha)
                }
            )
        }
    }
}
