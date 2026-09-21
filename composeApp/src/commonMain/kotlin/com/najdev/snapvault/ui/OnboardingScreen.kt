package com.najdev.snapvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.defaultZipDropFolder
import com.najdev.snapvault.ioDispatcher
import com.najdev.snapvault.openUrl
import com.najdev.snapvault.revealInFileManager
import com.najdev.snapvault.supportsFileManager
import com.najdev.snapvault.onboarding.DropFolderOutcome
import com.najdev.snapvault.onboarding.ONBOARDING_STEP_COUNT
import com.najdev.snapvault.onboarding.OnboardingStep
import com.najdev.snapvault.onboarding.next
import com.najdev.snapvault.onboarding.previous
import com.najdev.snapvault.ui.theme.SnapVaultColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

/**
 * The first-launch walkthrough.
 *
 * A full-window takeover rather than a fourth navigation destination: it is read once and then
 * never again, and a permanent tab would cost every returning user sidebar space for it. Skip
 * is always visible — someone who arrived from the video should be able to leave in one click
 * — and both leaving and finishing persist identically, so neither returns on the next launch.
 *
 * Replayed from Settings through the same composable; see [onSkip] and [onFinish], which the
 * host points at "close the overlay" in that case rather than at the preference write.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onSkip: () -> Unit,
    windowSize: WindowSize = WindowSize.Expanded,
    dropFolderSuggestion: String? = defaultZipDropFolder(),
    // Suspend and defaulted to the IO dispatcher because it touches the disk; tests pass a
    // function over a FakeFileSystem so a run never creates a folder on the test machine.
    onCreateDropFolder: suspend (String) -> DropFolderOutcome = { path ->
        withContext(ioDispatcher) {
            com.najdev.snapvault.onboarding.prepareDropFolder(okio.FileSystem.SYSTEM, path)
        }
    },
    onRevealFolder: (String) -> Unit = ::revealInFileManager,
    canRevealFolder: Boolean = supportsFileManager,
    onOpenUrl: (String) -> Unit = ::openUrl,
    // Hoisted so both branches are reachable from a test: the button hides itself when this
    // is blank, and that used to be the only path with coverage because the resource was
    // empty until the walkthrough was recorded.
    videoUrl: String = stringResource(Res.string.onb_video_url),
) {
    var step by remember { mutableStateOf(OnboardingStep.entries.first()) }
    val compact = windowSize != WindowSize.Expanded

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(if (compact) 20.dp else 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OnboardingHeader(step = step, onSkip = onSkip)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(if (compact) 8.dp else 24.dp))
                when (step) {
                    OnboardingStep.RequestExport -> RequestExportStep(onOpenUrl, videoUrl)
                    OnboardingStep.DownloadZips -> DownloadZipsStep(
                        suggestion = dropFolderSuggestion,
                        onCreateDropFolder = onCreateDropFolder,
                        onRevealFolder = onRevealFolder,
                        canRevealFolder = canRevealFolder,
                    )
                    OnboardingStep.ChooseFolders -> ChooseFoldersStep()
                    OnboardingStep.Pipeline -> PipelineStep()
                    OnboardingStep.RunIt -> RunItStep()
                }
                Spacer(Modifier.height(8.dp))
            }

            OnboardingFooter(
                step = step,
                onBack = { step = step.previous() },
                onNext = { if (step == OnboardingStep.entries.last()) onFinish() else step = step.next() },
            )
        }
    }
}

// ── Chrome ───────────────────────────────────────────────────────────────────

@Composable
private fun OnboardingHeader(step: OnboardingStep, onSkip: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StepDots(step)
        TextButton(onClick = onSkip) {
            Text(stringResource(Res.string.onb_skip), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Progress as filled dots. Like the Dashboard stepper this conveys state with fill alone, so
 * each dot carries a spoken state description rather than relying on the colour.
 */
@Composable
private fun StepDots(current: OnboardingStep) {
    val done = stringResource(Res.string.onb_step_state_done)
    val isCurrent = stringResource(Res.string.onb_step_state_current)
    val upcoming = stringResource(Res.string.onb_step_state_upcoming)

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OnboardingStep.entries.forEach { entry ->
            val state = when {
                entry.ordinal < current.ordinal -> done
                entry == current -> isCurrent
                else -> upcoming
            }
            val label = stringResource(
                Res.string.onb_step_state,
                entry.ordinal + 1,
                ONBOARDING_STEP_COUNT,
                state,
            )
            Box(
                modifier = Modifier
                    .size(if (entry == current) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            entry.ordinal < current.ordinal -> SnapVaultColors.success
                            entry == current -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .semantics { contentDescription = label; stateDescription = state }
            )
        }
    }
}

@Composable
private fun OnboardingFooter(step: OnboardingStep, onBack: () -> Unit, onNext: () -> Unit) {
    val isLast = step == OnboardingStep.entries.last()
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(Res.string.onb_progress, step.ordinal + 1, ONBOARDING_STEP_COUNT),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Present but disabled on the first step rather than absent, so the footer does
            // not reflow under the user as they move through.
            OutlinedButton(
                onClick = onBack,
                enabled = step.ordinal > 0,
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.onb_back))
            }
            Button(onClick = onNext, shape = RoundedCornerShape(8.dp)) {
                Text(stringResource(if (isLast) Res.string.onb_finish else Res.string.onb_next))
            }
        }
    }
}

// ── Shared pieces ────────────────────────────────────────────────────────────

@Composable
private fun StepHeading(title: StringResource) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@Composable
private fun StepBody(text: StringResource) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A tinted aside for the thing that bites people if they skim the body. */
@Composable
private fun StepNote(text: String, icon: ImageVector? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    null,
                    tint = SnapVaultColors.info,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Opens an external page, falling back to a selectable address when no browser answers. */
@Composable
private fun LinkButton(
    label: String,
    url: String,
    accessibleLabel: String,
    onOpenUrl: (String) -> Unit,
    icon: ImageVector = Icons.AutoMirrored.Outlined.OpenInNew,
) {
    var failed by remember(url) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = {
                failed = false
                try {
                    onOpenUrl(url)
                } catch (_: Exception) {
                    failed = true
                }
            },
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            modifier = Modifier.semantics { contentDescription = accessibleLabel },
        ) {
            Icon(icon, null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
        if (failed) {
            Text(
                stringResource(Res.string.support_open_failed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            SelectionContainer { Text(url, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

// ── Steps ────────────────────────────────────────────────────────────────────

@Composable
private fun RequestExportStep(onOpenUrl: (String) -> Unit, videoUrl: String) {
    StepHeading(Res.string.onb_request_title)
    StepBody(Res.string.onb_request_body)
    StepNote(stringResource(Res.string.onb_request_note), Icons.Outlined.Schedule)
    LinkButton(
        label = stringResource(Res.string.onb_request_action),
        url = stringResource(Res.string.onb_request_url),
        accessibleLabel = stringResource(Res.string.onb_request_action),
        onOpenUrl = onOpenUrl,
    )
    // A blank URL hides the button rather than offering a link that goes nowhere — the same
    // treatment platformSupportPageUrl gets. It shipped blank until the walkthrough existed.
    if (videoUrl.isNotBlank()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinkButton(
                label = stringResource(Res.string.onb_video_action),
                url = videoUrl,
                accessibleLabel = stringResource(Res.string.onb_video_accessible_label),
                onOpenUrl = onOpenUrl,
                icon = Icons.Outlined.PlayCircleOutline,
            )
            Text(
                stringResource(Res.string.onb_video_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadZipsStep(
    suggestion: String?,
    onCreateDropFolder: suspend (String) -> DropFolderOutcome,
    onRevealFolder: (String) -> Unit,
    canRevealFolder: Boolean,
) {
    val scope = rememberCoroutineScope()
    var outcome by remember(suggestion) { mutableStateOf<DropFolderOutcome?>(null) }
    var working by remember(suggestion) { mutableStateOf(false) }

    StepHeading(Res.string.onb_download_title)
    StepBody(Res.string.onb_download_body)

    if (suggestion.isNullOrBlank()) {
        StepNote(stringResource(Res.string.onb_download_folder_mobile), Icons.Outlined.FolderOpen)
        return
    }

    StepBody(Res.string.onb_download_folder_intro)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SelectionContainer {
                Text(
                    suggestion,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!working) {
                            working = true
                            scope.launch {
                                try {
                                    outcome = onCreateDropFolder(suggestion)
                                } finally {
                                    working = false
                                }
                            }
                        }
                    },
                    enabled = !working,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Outlined.CreateNewFolder, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.onb_download_folder_create))
                }
                // Only offered once the folder is known to exist — revealing a path that was
                // never created opens the parent and reads as a bug.
                if (canRevealFolder && outcome != null && outcome !is DropFolderOutcome.Failed) {
                    OutlinedButton(
                        onClick = { onRevealFolder(suggestion) },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(Res.string.onb_download_folder_reveal))
                    }
                }
            }
            when (val o = outcome) {
                DropFolderOutcome.Created -> FolderResult(stringResource(Res.string.onb_download_folder_created), ok = true)
                DropFolderOutcome.AlreadyExisted -> FolderResult(stringResource(Res.string.onb_download_folder_existed), ok = true)
                is DropFolderOutcome.Failed -> FolderResult(stringResource(Res.string.onb_download_folder_failed, o.reason), ok = false)
                null -> Unit
            }
        }
    }
    Text(
        stringResource(Res.string.onb_download_folder_own),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FolderResult(text: String, ok: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (ok) SnapVaultColors.success else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ChooseFoldersStep() {
    StepHeading(Res.string.onb_folders_title)
    StepBody(Res.string.onb_folders_body)
    StepNote(stringResource(Res.string.onb_folders_space), Icons.Outlined.FolderOpen)
    StepNote(stringResource(Res.string.onb_folders_resume), Icons.Outlined.Schedule)
}

@Composable
private fun PipelineStep() {
    StepHeading(Res.string.onb_pipeline_title)
    StepBody(Res.string.onb_pipeline_body)
    PhaseCard(Icons.Outlined.Download, Res.string.onb_pipeline_download_title, Res.string.onb_pipeline_download_body)
    PhaseCard(Icons.Outlined.Schedule, Res.string.onb_pipeline_metadata_title, Res.string.onb_pipeline_metadata_body)
    PhaseCard(Icons.Outlined.Layers, Res.string.onb_pipeline_combine_title, Res.string.onb_pipeline_combine_body)
    // The destructive one, tinted to match the warning it carries on the Dashboard.
    PhaseCard(
        Icons.Outlined.CleaningServices,
        Res.string.onb_pipeline_dedupe_title,
        Res.string.onb_pipeline_dedupe_body,
        tint = SnapVaultColors.warning,
    )
}

@Composable
private fun PhaseCard(
    icon: ImageVector,
    title: StringResource,
    body: StringResource,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunItStep() {
    StepHeading(Res.string.onb_run_title)
    StepBody(Res.string.onb_run_body)
    StepBody(Res.string.onb_run_review)
    StepNote(stringResource(Res.string.onb_run_replay))
}
