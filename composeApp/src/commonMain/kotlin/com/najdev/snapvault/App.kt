package com.najdev.snapvault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.ui.DashboardScreen
import com.najdev.snapvault.ui.LibraryScreen
import com.najdev.snapvault.ui.SettingsScreen
import com.najdev.snapvault.ui.components.AppSidebar
import com.najdev.snapvault.ui.components.AppTopBar
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import com.najdev.snapvault.viewmodel.DashboardViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.FileSystem

enum class Screen { Dashboard, Library, Settings }
enum class ImportMode { Legacy, Zip }
enum class ZipSourceMode { SingleFile, Folder }

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
    var isDarkMode by remember { mutableStateOf(loadThemePreference()) }
    var workers by remember { mutableStateOf(loadWorkersPreference()) }

    var hasExifTool by remember { mutableStateOf(false) }
    var hasFFmpeg by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }

    val dashboardViewModel = remember {
        DashboardViewModel(zipPipelineRunner, mediaProcessor, fileSystem, pickers)
    }
    DisposableEffect(Unit) { onDispose { dashboardViewModel.dispose() } }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        hasExifTool = mediaProcessor.checkExifTool()
        hasFFmpeg = mediaProcessor.checkFFmpeg()
    }

    SnapVaultTheme(darkMode = isDarkMode) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            AppTopBar(
                showWindowControls = showWindowControls,
                onClose = onCloseWindow,
                onMinimize = onMinimizeWindow,
                onMaximize = onMaximizeWindow,
            )

            Row(modifier = Modifier.fillMaxSize()) {
                AppSidebar(
                    currentScreen = currentScreen,
                    isRunning = dashboardViewModel.isRunning,
                    currentStep = dashboardViewModel.currentStep,
                    onNavigate = { currentScreen = it },
                )

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (currentScreen) {
                        Screen.Dashboard -> DashboardScreen(
                            viewModel = dashboardViewModel,
                            workers = workers,
                            onNavigateToSettings = { currentScreen = Screen.Settings },
                        )
                        Screen.Library -> LibraryScreen(
                            downloadFolder = dashboardViewModel.downloadFolder,
                            onOpenFolder = dashboardViewModel::pickOutputFolder,
                        )
                        Screen.Settings -> SettingsScreen(
                            hasExifTool = hasExifTool,
                            hasFFmpeg = hasFFmpeg,
                            onVerifyDependencies = {
                                hasExifTool = mediaProcessor.checkExifTool()
                                hasFFmpeg = mediaProcessor.checkFFmpeg()
                            },
                            onRunInstaller = {
                                coroutineScope.launch {
                                    isInstalling = true
                                    delay(2000)
                                    hasExifTool = mediaProcessor.checkExifTool()
                                    hasFFmpeg = mediaProcessor.checkFFmpeg()
                                    isInstalling = false
                                }
                            },
                            isInstalling = isInstalling,
                            workers = workers,
                            onWorkersChange = { workers = it; saveWorkersPreference(it) },
                            isDarkMode = isDarkMode,
                            onToggleDarkMode = { isDarkMode = it; saveThemePreference(it) },
                        )
                    }
                }
            }
        }
    }
}
