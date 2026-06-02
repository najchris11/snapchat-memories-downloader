package com.najdev.snapvault.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najdev.snapvault.ui.theme.ElectricPurple
import com.najdev.snapvault.ui.theme.InfoBlue
import com.najdev.snapvault.ui.theme.SurfaceContainerHigh
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

@Composable
fun EnvironmentScreen(
    hasExifTool: Boolean,
    hasFFmpeg: Boolean,
    onVerifyDependencies: () -> Unit,
    onRunInstaller: () -> Unit,
    isInstalling: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Header
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(Res.string.env_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(Res.string.env_intro),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Dependencies Bento Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DependencyCard(
                name = "ExifTool",
                description = "Enables metadata injection for GPS coordinates and timestamps.",
                status = if (hasExifTool) DependencyStatus.READY else DependencyStatus.MISSING,
                icon = Icons.Default.LocationOn,
                modifier = Modifier.weight(1f)
            )

            DependencyCard(
                name = "FFmpeg",
                description = "Handles video stitching and overlay compositions.",
                status = if (hasFFmpeg) DependencyStatus.READY else DependencyStatus.MISSING,
                icon = Icons.Default.PlayArrow,
                modifier = Modifier.weight(1f)
            )
        }

        // Configuration action box
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.widthIn(max = 480.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElectricPurple.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            tint = ElectricPurple,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Automatic Binary Setup",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Extract and verify ExifTool & FFmpeg executable engines bundled within the native application package.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    if (isInstalling) {
                        CircularProgressIndicator(color = ElectricPurple)
                    } else {
                        Button(
                            onClick = onRunInstaller,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricPurple)
                        ) {
                            Text("Extract Bundled Binaries", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "Verify System PATH & Local Installations",
                        fontSize = 12.sp,
                        color = InfoBlue,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { onVerifyDependencies() }
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

enum class DependencyStatus {
    READY, MISSING
}

@Composable
fun DependencyCard(
    name: String,
    description: String,
    status: DependencyStatus,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(180.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ElectricPurple.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = ElectricPurple, modifier = Modifier.size(18.dp))
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (status == DependencyStatus.READY) Color.Green.copy(alpha = 0.1f)
                            else Color.Red.copy(alpha = 0.1f)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (status == DependencyStatus.READY) stringResource(Res.string.status_ready).uppercase() else "NOT FOUND",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (status == DependencyStatus.READY) Color.Green else Color.Red
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(100))
                        .background(if (status == DependencyStatus.READY) Color.Green else Color.Red)
                )
                Text(
                    text = if (status == DependencyStatus.READY) "Binary detected (PATH or ~/.snapvault/bin/)" else "Binary not found anywhere",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}
