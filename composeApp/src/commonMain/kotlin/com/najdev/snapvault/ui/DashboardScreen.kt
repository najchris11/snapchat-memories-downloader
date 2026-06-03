package com.najdev.snapvault.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najdev.snapvault.ui.theme.ElectricPurple
import com.najdev.snapvault.ui.theme.InfoBlue
import com.najdev.snapvault.ui.theme.SecondaryBlue
import com.najdev.snapvault.ui.theme.SurfaceContainerLowest
import com.najdev.snapvault.ui.theme.TertiaryCyan
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

@Composable
fun DashboardScreen(
    htmlFile: String?,
    downloadFolder: String?,
    onSelectHtmlFile: () -> Unit,
    onSelectFolder: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onStartSync: (runDownload: Boolean, runMetadata: Boolean, runCombine: Boolean, runDedupe: Boolean, dryRun: Boolean, workers: Int) -> Unit,
    onStopSync: () -> Unit,
    isRunning: Boolean,
    logs: List<String>,
    progress: Float,
    progressText: String,
    etaText: String,
    speedText: String,
    currentStep: Int
) {
    var runDownload by remember { mutableStateOf(true) }
    var runMetadata by remember { mutableStateOf(true) }
    var runCombine by remember { mutableStateOf(true) }
    var runDedupe by remember { mutableStateOf(true) }
    var dryRun by remember { mutableStateOf(false) }
    var workers by remember { mutableStateOf(5) }

    Row(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // ── Left column: controls (40%) ──────────────────────────────────────
        Column(
            modifier = Modifier.weight(0.4f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Setup card
            ControlCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.RocketLaunch,
                                contentDescription = null,
                                tint = ElectricPurple,
                                modifier = Modifier.size(16.dp)
                            )
                            Column {
                                Text(
                                    stringResource(Res.string.dash_setup_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    stringResource(Res.string.dash_setup_subtitle),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                        Surface(
                            onClick = onNavigateToSettings,
                            color = Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(12.dp))
                                Text(
                                    stringResource(Res.string.dash_configure_btn),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
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

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(Res.string.dash_history_label),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilePickerBox(
                            icon = Icons.Outlined.FileOpen,
                            label = htmlFile ?: stringResource(Res.string.dash_history_placeholder),
                            onClick = onSelectHtmlFile,
                            isSelected = htmlFile != null
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(Res.string.dash_output_label),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilePickerBox(
                            icon = Icons.Outlined.FolderOpen,
                            label = downloadFolder ?: stringResource(Res.string.dash_output_placeholder),
                            onClick = onSelectFolder,
                            isSelected = downloadFolder != null
                        )
                    }
                }
            }

            // Pipeline options card
            ControlCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel(
                        icon = Icons.Outlined.AccountTree,
                        text = stringResource(Res.string.dash_pipeline_title)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PipelineItem(Icons.Outlined.CloudDownload, stringResource(Res.string.opt_download_memories), runDownload) { runDownload = it }
                        PipelineItem(Icons.Outlined.GpsFixed, stringResource(Res.string.opt_inject_gps), runMetadata) { runMetadata = it }
                        PipelineItem(Icons.Outlined.Layers, stringResource(Res.string.opt_combine_overlays), runCombine) { runCombine = it }
                        PipelineItem(Icons.Outlined.AutoDelete, stringResource(Res.string.opt_clean_duplicates), runDedupe) { runDedupe = it }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                    // Workers slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            stringResource(Res.string.dash_workers_label),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "$workers",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ElectricPurple,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Slider(
                        value = workers.toFloat(),
                        onValueChange = { workers = it.toInt() },
                        valueRange = 1f..10f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricPurple,
                            activeTrackColor = ElectricPurple,
                            inactiveTrackColor = Color.White.copy(alpha = 0.08f)
                        )
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onStartSync(runDownload, runMetadata, runCombine, runDedupe, dryRun, workers) },
                    enabled = !isRunning && htmlFile != null && downloadFolder != null,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(Res.string.btn_start_sync),
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                }

                Surface(
                    onClick = onStopSync,
                    enabled = isRunning,
                    color = if (isRunning) MaterialTheme.colorScheme.surfaceVariant else Color.White.copy(alpha = 0.04f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.size(52.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(Res.string.btn_stop),
                            tint = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }

        // ── Right column: terminal + progress (60%) ──────────────────────────
        Column(
            modifier = Modifier.weight(0.6f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Terminal
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Column {
                    // Terminal chrome
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                TerminalDot(Color(0xFFFF5F56))
                                TerminalDot(Color(0xFFFFBD2E))
                                TerminalDot(Color(0xFF27C93F))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(Res.string.dash_terminal_title),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                    }

                    val listState = rememberLazyListState()
                    val coroutineScope = rememberCoroutineScope()
                    LaunchedEffect(logs.size) {
                        if (logs.isNotEmpty()) {
                            coroutineScope.launch { listState.animateScrollToItem(logs.size - 1) }
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        items(logs) { log -> TerminalLogLine(log) }
                        if (isRunning) {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "$ ",
                                        color = ElectricPurple,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        "running_pipeline",
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                    BlinkingCursor()
                                }
                            }
                        }
                    }
                }
            }

            // Progress & stepper card
            ControlCard {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Stepper
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StepItem(1, stringResource(Res.string.dash_step_setup), currentStep == 0, currentStep > 0, Icons.Outlined.Edit)
                        StepperDivider(currentStep > 0)
                        StepItem(2, stringResource(Res.string.dash_step_syncing), currentStep == 1, currentStep > 1, Icons.Outlined.CloudSync)
                        StepperDivider(currentStep > 1)
                        StepItem(3, stringResource(Res.string.dash_step_processing), currentStep == 2, currentStep > 2, Icons.Outlined.AutoFixHigh)
                        StepperDivider(currentStep > 2)
                        StepItem(4, stringResource(Res.string.dash_step_complete), currentStep == 3, currentStep > 3, Icons.Outlined.TaskAlt)
                    }

                    // Progress bar
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                progressText.ifEmpty { stringResource(Res.string.status_idle) },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${(progress * 100).toInt()}%",
                                fontSize = 12.sp,
                                color = TertiaryCyan,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(100))
                                .background(Color.White.copy(alpha = 0.05f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .background(
                                        Brush.horizontalGradient(listOf(ElectricPurple, InfoBlue))
                                    )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                speedText,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                etaText,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Shared sub-components ────────────────────────────────────────────────────

@Composable
fun SectionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
fun ControlCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Box(modifier = Modifier.padding(20.dp)) { content() }
    }
}

@Composable
fun FilePickerBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    isSelected: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(SurfaceContainerLowest, RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            icon,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            stringResource(Res.string.browse_btn),
            fontSize = 10.sp,
            color = ElectricPurple,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PipelineItem(
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
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                icon,
                null,
                tint = if (checked) ElectricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp)
            )
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = ElectricPurple,
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
    Box(
        Modifier
            .padding(start = 3.dp)
            .width(7.dp)
            .height(14.dp)
            .alpha(alpha)
            .background(ElectricPurple)
    )
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (tag.isNotEmpty()) {
            Text(
                text = "[$tag]",
                color = when (tag) {
                    "SUCCESS", "DL", "DEDUPE" -> Color(0xFF4ADE80)
                    "ERROR" -> Color(0xFFF87171)
                    "WARN" -> Color(0xFFFBBF24)
                    "META" -> TertiaryCyan
                    "SKIP" -> SecondaryBlue.copy(alpha = 0.7f)
                    else -> InfoBlue
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = content,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}

@Composable
fun StepItem(
    step: Int,
    label: String,
    active: Boolean,
    complete: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(100))
                .background(
                    when {
                        complete -> ElectricPurple
                        active -> ElectricPurple.copy(alpha = 0.2f)
                        else -> Color.White.copy(alpha = 0.04f)
                    }
                )
                .border(
                    width = 1.5.dp,
                    color = if (active || complete) ElectricPurple else Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(100)
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                complete -> Icon(
                    Icons.Default.Check,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
                active -> Icon(
                    icon,
                    null,
                    tint = ElectricPurple,
                    modifier = Modifier.size(15.dp)
                )
                else -> Text(
                    step.toString(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active || complete) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        )
    }
}

@Composable
fun StepperDivider(filled: Boolean = false) {
    Box(
        Modifier
            .width(36.dp)
            .height(1.5.dp)
            .background(if (filled) ElectricPurple.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.06f))
    )
}
