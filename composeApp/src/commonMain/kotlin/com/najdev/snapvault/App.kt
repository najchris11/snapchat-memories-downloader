package com.najdev.snapvault

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.ui.DashboardScreen
import com.najdev.snapvault.ui.LibraryScreen
import com.najdev.snapvault.ui.PhoneRoot
import com.najdev.snapvault.ui.SettingsScreen
import com.najdev.snapvault.ui.components.AppSidebar
import com.najdev.snapvault.ui.components.AppTopBar
import com.najdev.snapvault.ui.theme.SnapVaultColors
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import com.najdev.snapvault.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem

enum class Screen { Dashboard, Library, Settings }
enum class ImportMode { Legacy, Zip }
enum class ZipSourceMode { Folder, MultipleFiles }

@Composable
fun App(
    pickers: PlatformPickers,
    mediaProcessor: MediaProcessor,
    zipPipelineRunner: ZipPipelineRunner,
    fileSystem: FileSystem,
    showWindowControls: Boolean = false,
    onCloseWindow: () -> Unit = {},
    onMinimizeWindow: () -> Unit = {},
    onMaximizeWindow: () -> Unit = {},
) {
    var currentScreen by remember { mutableStateOf(Screen.Dashboard) }
    var themeMode by remember { mutableStateOf(loadThemeModePreference()) }
    var layoutOverride by remember { mutableStateOf(loadLayoutOverride()) }
    val isDarkMode = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    var hasExifTool by remember { mutableStateOf(false) }
    var hasFFmpeg by remember { mutableStateOf(false) }

    val dashboardViewModel = remember {
        DashboardViewModel(zipPipelineRunner, mediaProcessor, fileSystem, pickers)
    }
    DisposableEffect(Unit) { onDispose { dashboardViewModel.dispose() } }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(ioDispatcher) {
            hasExifTool = mediaProcessor.checkExifTool()
            hasFFmpeg = mediaProcessor.checkFFmpeg()
        }
    }
    val onVerifyDependencies: () -> Unit = {
        scope.launch(ioDispatcher) {
            hasExifTool = mediaProcessor.checkExifTool()
            hasFFmpeg = mediaProcessor.checkFFmpeg()
        }
    }

    SnapVaultTheme(darkMode = isDarkMode) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val windowSize = getActiveWindowSize(maxWidth, layoutOverride)

            CompositionLocalProvider(LocalWindowSize provides windowSize) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                        // Top bar is hidden on compact screens to gain 56dp vertical space
                        if (windowSize != WindowSize.Compact) {
                            AppTopBar(
                                showWindowControls = showWindowControls,
                                onClose = onCloseWindow,
                                onMinimize = onMinimizeWindow,
                                onMaximize = onMaximizeWindow,
                            )
                        }

                        when (windowSize) {
                            WindowSize.Compact -> {
                                PhoneRoot(
                                    dashboardViewModel = dashboardViewModel,
                                    hasExifTool = hasExifTool,
                                    hasFFmpeg = hasFFmpeg,
                                    onVerifyDependencies = onVerifyDependencies,
                                    themeMode = themeMode,
                                    onThemeModeChange = { themeMode = it; saveThemeModePreference(it) },
                                    layoutOverride = layoutOverride,
                                    onLayoutOverrideChange = { layoutOverride = it; saveLayoutOverride(it) },
                                )
                            }
                            WindowSize.Medium -> {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    NavigationRail(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    ) {
                                        NavigationRailItem(
                                            selected = currentScreen == Screen.Dashboard,
                                            onClick = { currentScreen = Screen.Dashboard },
                                            icon = { Icon(if (currentScreen == Screen.Dashboard) Icons.Filled.Dashboard else Icons.Outlined.Dashboard, "Dashboard") },
                                            label = { Text("Dashboard") },
                                            colors = NavigationRailItemDefaults.colors(
                                                selectedIconColor = SnapVaultColors.electricPurple,
                                                indicatorColor = SnapVaultColors.electricPurple.copy(alpha = 0.12f)
                                            )
                                        )
                                        NavigationRailItem(
                                            selected = currentScreen == Screen.Library,
                                            onClick = { currentScreen = Screen.Library },
                                            icon = { Icon(if (currentScreen == Screen.Library) Icons.Filled.PhotoLibrary else Icons.Outlined.PhotoLibrary, "Library") },
                                            label = { Text("Library") },
                                            colors = NavigationRailItemDefaults.colors(
                                                selectedIconColor = SnapVaultColors.electricPurple,
                                                indicatorColor = SnapVaultColors.electricPurple.copy(alpha = 0.12f)
                                            )
                                        )
                                        NavigationRailItem(
                                            selected = currentScreen == Screen.Settings,
                                            onClick = { currentScreen = Screen.Settings },
                                            icon = { Icon(if (currentScreen == Screen.Settings) Icons.Filled.Settings else Icons.Outlined.Settings, "Settings") },
                                            label = { Text("Settings") },
                                            colors = NavigationRailItemDefaults.colors(
                                                selectedIconColor = SnapVaultColors.electricPurple,
                                                indicatorColor = SnapVaultColors.electricPurple.copy(alpha = 0.12f)
                                            )
                                        )
                                    }

                                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                        MainContentScreen(
                                            currentScreen = currentScreen,
                                            dashboardViewModel = dashboardViewModel,
                                            hasExifTool = hasExifTool,
                                            hasFFmpeg = hasFFmpeg,
                                            onVerifyDependencies = onVerifyDependencies,
                                            themeMode = themeMode,
                                            onThemeModeChange = { themeMode = it; saveThemeModePreference(it) },
                                            layoutOverride = layoutOverride,
                                            onLayoutOverrideChange = { layoutOverride = it; saveLayoutOverride(it) },
                                            onNavigateToSettings = { currentScreen = Screen.Settings }
                                        )
                                    }
                                }
                            }
                            WindowSize.Expanded -> {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    AppSidebar(
                                        currentScreen = currentScreen,
                                        isRunning = dashboardViewModel.isRunning,
                                        currentStep = dashboardViewModel.currentStep,
                                        onNavigate = { currentScreen = it },
                                    )

                                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                        MainContentScreen(
                                            currentScreen = currentScreen,
                                            dashboardViewModel = dashboardViewModel,
                                            hasExifTool = hasExifTool,
                                            hasFFmpeg = hasFFmpeg,
                                            onVerifyDependencies = onVerifyDependencies,
                                            themeMode = themeMode,
                                            onThemeModeChange = { themeMode = it; saveThemeModePreference(it) },
                                            layoutOverride = layoutOverride,
                                            onLayoutOverrideChange = { layoutOverride = it; saveLayoutOverride(it) },
                                            onNavigateToSettings = { currentScreen = Screen.Settings }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainContentScreen(
    currentScreen: Screen,
    dashboardViewModel: DashboardViewModel,
    hasExifTool: Boolean,
    hasFFmpeg: Boolean,
    onVerifyDependencies: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    layoutOverride: LayoutOverride,
    onLayoutOverrideChange: (LayoutOverride) -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    when (currentScreen) {
        Screen.Dashboard -> DashboardScreen(
            viewModel = dashboardViewModel,
            onNavigateToSettings = onNavigateToSettings,
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
