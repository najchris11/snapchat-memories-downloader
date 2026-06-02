package com.najdev.snapvault.ui

import androidx.compose.animation.*
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najdev.snapvault.ui.theme.ElectricPurple
import com.najdev.snapvault.ui.theme.InfoBlue
import com.najdev.snapvault.ui.theme.SlateBright
import com.najdev.snapvault.ui.theme.SurfaceContainerHigh
import com.najdev.snapvault.ui.theme.SurfaceContainerLowest
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

@Composable
fun DashboardScreen(
    htmlFile: String?,
    downloadFolder: String?,
    onSelectHtmlFile: () -> Unit,
    onSelectFolder: () -> Unit,
    onStartSync: (runDownload: Boolean, runMetadata: Boolean, runCombine: Boolean, runDedupe: Boolean, dryRun: Boolean, workers: Int) -> Unit,
    onStopSync: () -> Unit,
    isRunning: Boolean,
    logs: List<String>,
    progress: Float,
    progressText: String,
    etaText: String,
    speedText: String,
    currentStep: Int // 0: Setup, 1: Syncing, 2: Processing, 3: Complete
) {
    var runDownload by remember { mutableStateOf(true) }
    var runMetadata by remember { mutableStateOf(true) }
    var runCombine by remember { mutableStateOf(true) }
    var runDedupe by remember { mutableStateOf(true) }
    var dryRun by remember { mutableStateOf(false) }
    var workers by remember { mutableStateOf(5) }
    var logsExpanded by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // Left Column: Configuration (40%)
        Column(modifier = Modifier.weight(0.4f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(Res.string.config_section_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Source & Destination Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(Res.string.config_section_title),
                        fontWeight = FontWeight.Bold,
                        color = ElectricPurple,
                        fontSize = 12.sp
                    )

                    // HTML Picker
                    Column {
                        Text(stringResource(Res.string.source_dir_label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceContainerLowest, RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .clickable { onSelectHtmlFile() }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = htmlFile ?: "Select memories_history.html",
                                fontSize = 12.sp,
                                color = if (htmlFile != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(stringResource(Res.string.browse_btn), fontSize = 11.sp, color = ElectricPurple, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Folder Picker
                    Column {
                        Text(stringResource(Res.string.dest_dir_label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceContainerLowest, RoundedCornerShape(8.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                .clickable { onSelectFolder() }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.List, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = downloadFolder ?: "Select download folder",
                                fontSize = 12.sp,
                                color = if (downloadFolder != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(stringResource(Res.string.browse_btn), fontSize = 11.sp, color = ElectricPurple, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Pipeline Options Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(Res.string.options_section_title), fontWeight = FontWeight.Bold, color = ElectricPurple, fontSize = 12.sp)

                    PipelineToggle(
                        label = stringResource(Res.string.opt_download_memories),
                        checked = runDownload,
                        onCheckedChange = { runDownload = it },
                        icon = Icons.Default.Add
                    )
                    PipelineToggle(
                        label = stringResource(Res.string.opt_inject_gps),
                        checked = runMetadata,
                        onCheckedChange = { runMetadata = it },
                        icon = Icons.Default.LocationOn
                    )
                    PipelineToggle(
                        label = stringResource(Res.string.opt_combine_overlays),
                        checked = runCombine,
                        onCheckedChange = { runCombine = it },
                        icon = Icons.Default.Build
                    )
                    PipelineToggle(
                        label = stringResource(Res.string.opt_clean_duplicates),
                        checked = runDedupe,
                        onCheckedChange = { runDedupe = it },
                        icon = Icons.Default.Delete
                    )
                }
            }

            // Advanced Tuning Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.set_concurrency_label), fontWeight = FontWeight.Bold, color = ElectricPurple, fontSize = 12.sp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dry Run (Preview)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                        Switch(
                            checked = dryRun,
                            onCheckedChange = { dryRun = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = ElectricPurple)
                        )
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Parallel Worker Threads", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("$workers threads", fontSize = 13.sp, color = ElectricPurple, fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = workers.toFloat(),
                            onValueChange = { workers = it.toInt() },
                            valueRange = 1f..16f,
                            steps = 15,
                            colors = SliderDefaults.colors(thumbColor = ElectricPurple, activeTrackColor = ElectricPurple)
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Action Buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onStartSync(runDownload, runMetadata, runCombine, runDedupe, dryRun, workers) },
                    enabled = !isRunning && htmlFile != null && downloadFolder != null,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.btn_start_sync), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Button(
                    onClick = { onStopSync() },
                    enabled = isRunning,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }
        }

        // Right Column: Reimagined Sync Info & Collapsible Logs (60%)
        Column(modifier = Modifier.weight(0.6f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(Res.string.status_section_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Hero Sync Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Row(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isRunning) "Synchronization Active" else "Synchronization Inactive",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = if (isRunning) ElectricPurple else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            text = progressText.ifEmpty { "Idle — Configure directories and start sync." },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        if (isRunning) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                if (speedText.isNotEmpty()) {
                                    Badge(containerColor = SurfaceContainerHigh) {
                                        Text(speedText, modifier = Modifier.padding(4.dp), fontSize = 11.sp)
                                    }
                                }
                                if (etaText.isNotEmpty()) {
                                    Badge(containerColor = SurfaceContainerHigh) {
                                        Text(etaText, modifier = Modifier.padding(4.dp), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    // Large circular progress indicator
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
                        CircularProgressIndicator(
                            progress = progress,
                            modifier = Modifier.size(110.dp),
                            color = ElectricPurple,
                            strokeWidth = 8.dp,
                            trackColor = Color.White.copy(alpha = 0.05f)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${(progress * 100).toInt()}%", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("complete", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Sub-process status cards
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sub-Processes", fontWeight = FontWeight.Bold, color = ElectricPurple, fontSize = 12.sp)

                    SubProcessItem(
                        name = stringResource(Res.string.sub_process_metadata),
                        status = when {
                            !isRunning -> "Pending"
                            currentStep > 2 -> "Complete"
                            currentStep == 2 && runMetadata -> "Running"
                            else -> "Idle"
                        },
                        isActive = isRunning && currentStep == 2 && runMetadata,
                        isComplete = currentStep > 2 || (!runMetadata && isRunning)
                    )

                    SubProcessItem(
                        name = stringResource(Res.string.sub_process_verification),
                        status = when {
                            !isRunning -> "Pending"
                            currentStep > 3 -> "Complete"
                            currentStep == 3 -> "Running"
                            else -> "Idle"
                        },
                        isActive = isRunning && currentStep == 3,
                        isComplete = currentStep > 3
                    )
                }
            }

            // Expandable process logs accordion
            Card(
                modifier = Modifier.fillMaxWidth().animateContentSize(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { logsExpanded = !logsExpanded }.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(Res.string.logs_accordion_title), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Icon(
                            imageVector = if (logsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (logsExpanded) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                        
                        val listState = rememberLazyListState()
                        val coroutineScope = rememberCoroutineScope()
                        LaunchedEffect(logs.size) {
                            if (logs.isNotEmpty()) {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(logs.size - 1)
                                }
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().height(160.dp).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(logs) { log ->
                                val color = when {
                                    log.contains("[SUCCESS]") || log.contains("[DOWNLOADED]") -> Color.Green
                                    log.contains("[ERROR]") -> Color.Red
                                    log.contains("[WARN]") -> Color.Yellow
                                    else -> MaterialTheme.colorScheme.onBackground
                                }
                                Text(
                                    text = log,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = color
                                )
                            }
                        }
                    }
                }
            }
            
            // Stepper Status Indicator
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StepItem(step = 1, label = "Setup", active = currentStep == 0, complete = currentStep > 0)
                    Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 8.dp))
                    StepItem(step = 2, label = "Syncing", active = currentStep == 1, complete = currentStep > 1)
                    Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 8.dp))
                    StepItem(step = 3, label = "Processing", active = currentStep == 2, complete = currentStep > 2)
                    Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 8.dp))
                    StepItem(step = 4, label = "Complete", active = currentStep == 3, complete = currentStep > 3)
                }
            }
        }
    }
}

@Composable
fun SubProcessItem(
    name: String,
    status: String,
    isActive: Boolean,
    isComplete: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isActive) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = ElectricPurple)
            } else if (isComplete) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green, modifier = Modifier.size(14.dp))
            }
            Text(
                text = status,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    isComplete -> Color.Green
                    isActive -> ElectricPurple
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
fun PipelineToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 13.sp)
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = ElectricPurple, checkmarkColor = Color.White)
        )
    }
}

@Composable
fun StepItem(
    step: Int,
    label: String,
    active: Boolean,
    complete: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(100))
                .background(
                    when {
                        complete -> ElectricPurple
                        active -> ElectricPurple.copy(alpha = 0.2f)
                        else -> SurfaceContainerHigh
                    }
                )
                .border(
                    width = 1.dp,
                    color = when {
                        active -> ElectricPurple
                        else -> Color.Transparent
                    },
                    shape = RoundedCornerShape(100)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (complete) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            } else {
                Text(
                    text = step.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (active) ElectricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) ElectricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
