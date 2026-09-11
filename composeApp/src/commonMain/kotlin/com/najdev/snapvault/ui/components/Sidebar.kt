package com.najdev.snapvault.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.Screen
import com.najdev.snapvault.ui.navIconActive
import com.najdev.snapvault.ui.navIconInactive
import com.najdev.snapvault.ui.navLabel
import com.najdev.snapvault.ui.theme.SnapVaultColors
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
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            // The Library used to be disabled while a run was in progress, and the bottom
            // bar never was. The scan is read-only and runs on ioDispatcher, so the lock
            // bought nothing — and it explained itself nowhere, rendering at 30% alpha with
            // no tooltip and no cursor change. A control that refuses without saying why
            // reads as a bug.
            Screen.entries.forEachIndexed { index, screen ->
                if (index > 0) Spacer(Modifier.height(2.dp))
                SidebarNavItem(
                    label = screen.navLabel(),
                    iconActive = screen.navIconActive,
                    iconInactive = screen.navIconInactive,
                    active = currentScreen == screen,
                    onClick = { onNavigate(screen) }
                )
            }

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
            isRunning -> MaterialTheme.colorScheme.primary
            currentStep == 3 -> SnapVaultColors.success
            else -> MaterialTheme.colorScheme.onSurfaceVariant
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
        Text(text = statusLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = statusColor)
        Spacer(Modifier.weight(1f))
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
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
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bgColor by animateColorAsState(if (active && enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
    val contentColor by animateColorAsState(
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            active -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .selectable(
                selected = active,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
        )
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (active && enabled) iconActive else iconInactive,
                contentDescription = null,
                tint = if (active && enabled) MaterialTheme.colorScheme.primary else contentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor
            )
        }
    }
}
