package com.najdev.snapvault

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.ui.DashboardScreen
import com.najdev.snapvault.ui.LibraryScreen
import com.najdev.snapvault.ui.PhoneRoot
import com.najdev.snapvault.ui.SettingsScreen
import com.najdev.snapvault.ui.components.AppSidebar
import com.najdev.snapvault.ui.components.AppTopBar
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

    // Off the UI thread: on first launch these unzip the bundled binaries (~27 MB
    // compressed ffmpeg) and spawn `which`, which would freeze the window for seconds.
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
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            // System-bar padding matters on mobile (edge-to-edge); insets are zero on desktop.
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                AppTopBar(
                    showWindowControls = showWindowControls,
                    onClose = onCloseWindow,
                    onMinimize = onMinimizeWindow,
                    onMaximize = onMaximizeWindow,
                )

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    if (getActiveWindowSize(maxWidth, layoutOverride) == WindowSize.Compact) {
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
                    } else {
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
                                        onThemeModeChange = { themeMode = it; saveThemeModePreference(it) },
                                        layoutOverride = layoutOverride,
                                        onLayoutOverrideChange = { layoutOverride = it; saveLayoutOverride(it) },
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
