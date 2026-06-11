package com.najdev.snapvault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.najdev.snapvault.LayoutOverride
import com.najdev.snapvault.Screen
import com.najdev.snapvault.ui.theme.ElectricPurple
import com.najdev.snapvault.viewmodel.DashboardViewModel

@Composable
fun PhoneRoot(
    dashboardViewModel: DashboardViewModel,
    hasExifTool: Boolean,
    hasFFmpeg: Boolean,
    onVerifyDependencies: () -> Unit,
    workers: Int,
    onWorkersChange: (Int) -> Unit,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean) -> Unit,
    layoutOverride: LayoutOverride,
    onLayoutOverrideChange: (LayoutOverride) -> Unit
) {
    var currentScreen by remember { mutableStateOf(Screen.Dashboard) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = currentScreen == Screen.Dashboard,
                    onClick = { currentScreen = Screen.Dashboard },
                    label = { Text("Dashboard", fontSize = 10.sp) },
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == Screen.Dashboard) Icons.Filled.Dashboard else Icons.Outlined.Dashboard,
                            contentDescription = "Dashboard"
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricPurple,
                        selectedTextColor = ElectricPurple,
                        indicatorColor = ElectricPurple.copy(alpha = 0.12f)
                    )
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.Library,
                    onClick = { currentScreen = Screen.Library },
                    label = { Text("Library", fontSize = 10.sp) },
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == Screen.Library) Icons.Filled.PhotoLibrary else Icons.Outlined.PhotoLibrary,
                            contentDescription = "Library"
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricPurple,
                        selectedTextColor = ElectricPurple,
                        indicatorColor = ElectricPurple.copy(alpha = 0.12f)
                    )
                )
                NavigationBarItem(
                    selected = currentScreen == Screen.Settings,
                    onClick = { currentScreen = Screen.Settings },
                    label = { Text("Settings", fontSize = 10.sp) },
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == Screen.Settings) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "Settings"
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricPurple,
                        selectedTextColor = ElectricPurple,
                        indicatorColor = ElectricPurple.copy(alpha = 0.12f)
                    )
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
                    workers = workers,
                    onNavigateToSettings = { currentScreen = Screen.Settings }
                )
                Screen.Library -> LibraryScreen(
                    downloadFolder = dashboardViewModel.downloadFolder,
                    onOpenFolder = dashboardViewModel::pickOutputFolder
                )
                Screen.Settings -> SettingsScreen(
                    hasExifTool = hasExifTool,
                    hasFFmpeg = hasFFmpeg,
                    onVerifyDependencies = onVerifyDependencies,
                    downloadFolder = dashboardViewModel.downloadFolder,
                    onResetIndex = { dashboardViewModel.resetVaultIndex() },
                    onEditOutputPath = { dashboardViewModel.pickOutputFolder() },
                    workers = workers,
                    onWorkersChange = onWorkersChange,
                    isDarkMode = isDarkMode,
                    onToggleDarkMode = onToggleDarkMode,
                    layoutOverride = layoutOverride,
                    onLayoutOverrideChange = onLayoutOverrideChange
                )
            }
        }
    }
}
