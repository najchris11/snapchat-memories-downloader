package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.najdev.snapvault.LayoutOverride
import com.najdev.snapvault.Screen
import com.najdev.snapvault.ThemeMode
import com.najdev.snapvault.ui.theme.SnapVaultColors
import com.najdev.snapvault.viewmodel.DashboardViewModel

// Compact-width root: replaces the desktop sidebar with a bottom NavigationBar.
// Hosts the same three screens as the expanded layout.
@Composable
fun PhoneRoot(
    dashboardViewModel: DashboardViewModel,
    hasExifTool: Boolean,
    hasFFmpeg: Boolean,
    onVerifyDependencies: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    layoutOverride: LayoutOverride,
    onLayoutOverrideChange: (LayoutOverride) -> Unit,
) {
    var currentScreen by remember { mutableStateOf(Screen.Dashboard) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                tonalElevation = 0.dp
            ) {
                PhoneNavItem(
                    label = "Dashboard",
                    selected = currentScreen == Screen.Dashboard,
                    selectedIcon = Icons.Filled.Dashboard,
                    unselectedIcon = Icons.Outlined.Dashboard,
                    onClick = { currentScreen = Screen.Dashboard },
                )
                PhoneNavItem(
                    label = "Library",
                    selected = currentScreen == Screen.Library,
                    selectedIcon = Icons.Filled.PhotoLibrary,
                    unselectedIcon = Icons.Outlined.PhotoLibrary,
                    onClick = { currentScreen = Screen.Library },
                )
                PhoneNavItem(
                    label = "Settings",
                    selected = currentScreen == Screen.Settings,
                    selectedIcon = Icons.Filled.Settings,
                    unselectedIcon = Icons.Outlined.Settings,
                    onClick = { currentScreen = Screen.Settings },
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentScreen) {
                Screen.Dashboard -> DashboardScreen(
                    viewModel = dashboardViewModel,
                    onNavigateToSettings = { currentScreen = Screen.Settings },
                )
                Screen.Library -> LibraryScreen(
                    downloadFolder = dashboardViewModel.downloadFolder,
                    onOpenFolder = dashboardViewModel::pickOutputFolder,
                )
                Screen.Settings -> SettingsScreen(
                    hasExifTool = hasExifTool,
                    hasFFmpeg = hasFFmpeg,
                    onVerifyDependencies = onVerifyDependencies,
                    downloadFolder = dashboardViewModel.downloadFolder,
                    onResetIndex = { dashboardViewModel.resetVaultIndex() },
                    onEditOutputPath = { dashboardViewModel.pickOutputFolder() },
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    layoutOverride = layoutOverride,
                    onLayoutOverrideChange = onLayoutOverrideChange,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PhoneNavItem(
    label: String,
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        icon = {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = label
            )
        },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = SnapVaultColors.electricPurple,
            selectedTextColor = SnapVaultColors.electricPurple,
            indicatorColor = SnapVaultColors.electricPurple.copy(alpha = 0.12f)
        )
    )
}
