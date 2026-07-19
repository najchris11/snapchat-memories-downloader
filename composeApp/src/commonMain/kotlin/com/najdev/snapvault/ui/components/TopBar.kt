package com.najdev.snapvault.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najdev.snapvault.AppBuildConfig
import com.najdev.snapvault.DraggableArea
import com.najdev.snapvault.ui.theme.SnapVaultColors
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

@Composable
fun AppTopBar(
    showWindowControls: Boolean,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
) {
    DraggableArea {
        Surface(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Brand
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Image(
                        painter = painterResource(Res.drawable.ic_launcher),
                        contentDescription = stringResource(Res.string.app_name),
                        modifier = Modifier.size(24.dp),
                        contentScale = ContentScale.Fit
                    )
                    Text(
                        text = stringResource(Res.string.app_name),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                        letterSpacing = (-0.5).sp
                    )
                    VersionBadge(AppBuildConfig.VERSION)
                    if (AppBuildConfig.IS_DEBUG) DebugBadge()
                }

                // Window Controls
                if (showWindowControls) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        WindowControlButton("−", onMinimize)
                        WindowControlButton("▢", onMaximize)
                        WindowControlButton("×", onClose)
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionBadge(version: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(SnapVaultColors.electricPurple.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(version, fontSize = 10.sp, color = SnapVaultColors.electricPurple, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun DebugBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(SnapVaultColors.warning.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text("DEBUG", fontSize = 9.sp, color = SnapVaultColors.warning, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun WindowControlButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontWeight = FontWeight.Normal
        )
    }
}
