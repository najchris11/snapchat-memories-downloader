package com.najdev.snapvault.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najdev.snapvault.Screen
import com.najdev.snapvault.ui.theme.ElectricPurple
import org.jetbrains.compose.resources.stringResource
import snapchat_memories_downloader.composeapp.generated.resources.*

@Composable
fun AppSidebar(
    currentScreen: Screen,
    isRunning: Boolean,
    currentStep: Int,
    onNavigate: (Screen) -> Unit,
) {
    Surface(
        modifier = Modifier.width(220.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            SidebarNavItem(
                label = stringResource(Res.string.nav_dashboard),
                iconActive = Icons.Filled.Dashboard,
                iconInactive = Icons.Outlined.Dashboard,
                active = currentScreen == Screen.Dashboard,
                onClick = { onNavigate(Screen.Dashboard) }
            )
            Spacer(Modifier.height(2.dp))
            SidebarNavItem(
                label = stringResource(Res.string.nav_library),
                iconActive = Icons.Filled.PhotoLibrary,
                iconInactive = Icons.Outlined.PhotoLibrary,
                active = currentScreen == Screen.Library,
                onClick = { onNavigate(Screen.Library) }
            )
            Spacer(Modifier.height(2.dp))
            SidebarNavItem(
                label = stringResource(Res.string.nav_settings),
                iconActive = Icons.Filled.Tune,
                iconInactive = Icons.Outlined.Tune,
                active = currentScreen == Screen.Settings,
                onClick = { onNavigate(Screen.Settings) }
            )

            Spacer(Modifier.weight(1f))
            StatusChip(isRunning = isRunning, currentStep = currentStep)
        }
    }
}

@Composable
private fun StatusChip(isRunning: Boolean, currentStep: Int) {
    val statusLabel = when {
        isRunning -> stringResource(Res.string.nav_status_running)
        currentStep == 3 -> stringResource(Res.string.nav_status_complete)
        else -> stringResource(Res.string.nav_status_idle)
    }
    val statusColor by animateColorAsState(
        when {
            isRunning -> ElectricPurple
            currentStep == 3 -> Color(0xFF4ADE80)
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(100)).background(statusColor))
        Text(text = statusLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = statusColor)
        Spacer(Modifier.weight(1f))
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = ElectricPurple
            )
        }
    }
}

@Composable
fun SidebarNavItem(
    label: String,
    iconActive: ImageVector,
    iconInactive: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bgColor by animateColorAsState(if (active) ElectricPurple.copy(alpha = 0.12f) else Color.Transparent)
    val contentColor by animateColorAsState(
        if (active) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .background(if (active) ElectricPurple else Color.Transparent)
        )
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (active) iconActive else iconInactive,
                contentDescription = null,
                tint = if (active) ElectricPurple else contentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor
            )
        }
    }
}
