package com.najdev.snapvault.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najdev.snapvault.AppBuildConfig
import com.najdev.snapvault.LocalWindowSize
import com.najdev.snapvault.WindowSize
import com.najdev.snapvault.binaryInstallHint
import com.najdev.snapvault.isMobileBuild
import com.najdev.snapvault.ui.theme.ElectricPurple
import com.najdev.snapvault.ui.theme.InfoBlue
import com.najdev.snapvault.ui.theme.SnapVaultColors
import com.najdev.snapvault.LayoutOverride
import com.najdev.snapvault.ThemeMode
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

@Composable
fun SettingsScreen(
    hasExifTool: Boolean,
    hasFFmpeg: Boolean,
    onVerifyDependencies: () -> Unit,
    downloadFolder: String?,
    onResetIndex: () -> Unit,
    onEditOutputPath: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    layoutOverride: LayoutOverride,
    onLayoutOverrideChange: (LayoutOverride) -> Unit,
) {
    val windowSize = LocalWindowSize.current
    val isCompact = windowSize == WindowSize.Compact

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(if (isCompact) 16.dp else 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = if (isMobileBuild) "Manage mobile app preferences and system capabilities." else "Manage system dependencies and utility preferences.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }

        // ── General Settings ──────────────────────────────────────────────────
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsSectionLabel(
                    icon = Icons.Outlined.Tune,
                    text = stringResource(Res.string.set_general_title)
                )

                // Theme mode selector
                if (isCompact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.DarkMode, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                            Text(stringResource(Res.string.set_theme_label), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            ThemeMode.values().forEach { mode ->
                                val active = themeMode == mode
                                val label = when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (active) SnapVaultColors.electricPurple.copy(alpha = 0.15f) else Color.Transparent)
                                        .clickable { onThemeModeChange(mode) }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) SnapVaultColors.electricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.DarkMode,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(stringResource(Res.string.set_theme_label), fontSize = 13.sp)
                            Text(
                                "Choose between light, dark, or system default theme.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .padding(3.dp)
                        ) {
                            ThemeMode.values().forEach { mode ->
                                val active = themeMode == mode
                                val label = when (mode) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (active) SnapVaultColors.electricPurple.copy(alpha = 0.15f) else Color.Transparent)
                                        .clickable { onThemeModeChange(mode) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) SnapVaultColors.electricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Layout selector
                if (isCompact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Dashboard, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                            Text("Layout", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            LayoutOverride.values().forEach { option ->
                                val active = layoutOverride == option
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (active) SnapVaultColors.electricPurple.copy(alpha = 0.15f) else Color.Transparent)
                                        .clickable { onLayoutOverrideChange(option) }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = option.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) SnapVaultColors.electricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Dashboard,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("Layout", fontSize = 13.sp)
                            Text(
                                "Auto switches by window width; Compact forces the phone layout.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                .padding(3.dp)
                        ) {
                            LayoutOverride.values().forEach { option ->
                                val active = layoutOverride == option
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(if (active) SnapVaultColors.electricPurple.copy(alpha = 0.15f) else Color.Transparent)
                                        .clickable { onLayoutOverrideChange(option) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = option.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) SnapVaultColors.electricPurple else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── System Capabilities / Dependencies ───────────────────────────────────────────────
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsSectionLabel(
                    icon = Icons.Outlined.Terminal,
                    text = if (isMobileBuild) "System Capabilities" else stringResource(Res.string.set_deps_title)
                )

                if (isMobileBuild) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DependencyItem(
                            name = "Photo Metadata",
                            description = "EXIF tagging via ExifInterface",
                            status = DependencyStatus.READY,
                            icon = Icons.Outlined.GpsFixed
                        )
                        DependencyItem(
                            name = "Video Processing",
                            description = "Media3 video overlay combine coming soon",
                            status = DependencyStatus.MISSING,
                            icon = Icons.Outlined.Movie
                        )
                    }
                } else if (isCompact) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DependencyItem(
                            name = "ExifTool",
                            description = "GPS metadata injection",
                            status = if (hasExifTool) DependencyStatus.READY else DependencyStatus.MISSING,
                            icon = Icons.Outlined.GpsFixed
                        )
                        DependencyItem(
                            name = "FFmpeg",
                            description = "Video overlay processing",
                            status = if (hasFFmpeg) DependencyStatus.READY else DependencyStatus.MISSING,
                            icon = Icons.Outlined.Movie
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        DependencyItem(
                            name = "ExifTool",
                            description = "GPS metadata injection",
                            status = if (hasExifTool) DependencyStatus.READY else DependencyStatus.MISSING,
                            icon = Icons.Outlined.GpsFixed,
                            modifier = Modifier.weight(1f)
                        )
                        DependencyItem(
                            name = "FFmpeg",
                            description = "Video overlay processing",
                            status = if (hasFFmpeg) DependencyStatus.READY else DependencyStatus.MISSING,
                            icon = Icons.Outlined.Movie,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (!isMobileBuild && (!hasExifTool || !hasFFmpeg)) {
                    val hint = binaryInstallHint()
                    if (hint.isNotEmpty()) {
                        Text(
                            text = hint,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onVerifyDependencies,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Re-check Dependencies", fontSize = 12.sp)
                    }
                }
            }
        }

        // ── Data & Storage ────────────────────────────────────────────────────
        SettingsCard {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsSectionLabel(
                    icon = Icons.Outlined.Storage,
                    text = "Data & Storage"
                )

                // Vault output folder
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Vault Output Path", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            downloadFolder ?: "No directory set",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = onEditOutputPath,
                        colors = ButtonDefaults.buttonColors(containerColor = SnapVaultColors.electricPurple.copy(alpha = 0.15f))
                    ) {
                        Text("Change", fontSize = 12.sp, color = SnapVaultColors.electricPurple)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                // Reset Vault Index
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Vault Index", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Clear vault_index.json to force a full re-scan on next launch.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    OutlinedButton(
                        onClick = onResetIndex,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reset Index", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(modifier = Modifier.padding(20.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsSectionLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = SnapVaultColors.electricPurple, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}

private enum class DependencyStatus { READY, MISSING }

@Composable
private fun DependencyItem(
    name: String,
    description: String,
    status: DependencyStatus,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    val isReady = status == DependencyStatus.READY

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (isReady) SnapVaultColors.success.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isReady) SnapVaultColors.success.copy(alpha = 0.1f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isReady) SnapVaultColors.success else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isReady) SnapVaultColors.success.copy(alpha = 0.15f)
                                else SnapVaultColors.warning.copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (isReady) "READY" else "MISSING",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isReady) SnapVaultColors.success else SnapVaultColors.warning
                        )
                    }
                }
                Text(
                    description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
