package com.najdev.snapvault

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.najdev.snapvault.downloader.ZipPipelineRunner
import com.najdev.snapvault.metadata.MediaProcessor
import com.najdev.snapvault.onboarding.OnboardingMemory
import com.najdev.snapvault.onboarding.shouldShowOnboarding
import com.najdev.snapvault.ui.DashboardScreen
import com.najdev.snapvault.ui.OnboardingScreen
import com.najdev.snapvault.ui.LibraryScreen
import com.najdev.snapvault.ui.PhoneRoot
import com.najdev.snapvault.ui.SettingsScreen
import com.najdev.snapvault.ui.components.AppSidebar
import com.najdev.snapvault.ui.components.AppTopBar
import com.najdev.snapvault.ui.components.UnsavedFavoritesDialog
import com.najdev.snapvault.ui.theme.SnapVaultTheme
import com.najdev.snapvault.viewmodel.DashboardViewModel
import io.ktor.client.HttpClient
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
    // Bumped by the host each time the OS asks to close the window. A counter rather than a
    // flag, so a second request after "keep open" is still a change the effect below sees.
    closeRequests: Int = 0,
    // Called only once the view model is ready to exit — never directly by a close control.
    onCloseWindow: () -> Unit = {},
    onMinimizeWindow: () -> Unit = {},
    onMaximizeWindow: () -> Unit = {},
    // Reaches the machine's preference store by default, so tests that are not about it pass
    // OutputFolderMemory.None rather than reading and writing real settings.
    outputFolderMemory: OutputFolderMemory = OutputFolderMemory.Platform,
    // Same reason as outputFolderMemory above: the default reaches the real network, so a UI
    // test that is not about downloading passes a mock engine rather than letting a run make
    // a live request. A real request made the run's duration depend on the machine's network,
    // which is what made ControlsDuringARunTest flaky under full-suite load.
    httpClientFactory: () -> HttpClient = { HttpClient() },
    // Same reason as outputFolderMemory: the default reads and writes this machine's real
    // preference store, so a UI test that is not about onboarding would both inherit
    // whatever this machine holds and then write to it. OnboardingMemory.None behaves like
    // a user who has already been through the flow, which is what those tests want.
    onboardingMemory: OnboardingMemory = OnboardingMemory.Platform,
) {
    var currentScreen by remember { mutableStateOf(Screen.Dashboard) }
    // Read once, at composition. The flow is a takeover rather than a destination, so it
    // is a flag over the whole root rather than a Screen entry — see OnboardingScreen.
    var showOnboarding by remember { mutableStateOf(shouldShowOnboarding(onboardingMemory)) }
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
        DashboardViewModel(
            zipPipelineRunner, mediaProcessor, fileSystem, pickers,
            httpClientFactory = httpClientFactory,
            outputFolderMemory = outputFolderMemory,
        )
    }
    DisposableEffect(Unit) { onDispose { dashboardViewModel.dispose() } }

    // Every way of closing goes through the view model, which lets pending favorites land and
    // stops any run before saying it is safe to exit. See DashboardViewModel.CloseState.
    LaunchedEffect(closeRequests) {
        if (closeRequests > 0) dashboardViewModel.requestClose()
    }
    val closeState = dashboardViewModel.closeState
    LaunchedEffect(closeState) {
        if (closeState == DashboardViewModel.CloseState.ReadyToExit) onCloseWindow()
    }
    if (closeState is DashboardViewModel.CloseState.UnsavedFavorites) {
        UnsavedFavoritesDialog(
            count = closeState.count,
            onKeepOpen = dashboardViewModel::keepOpen,
            onQuitAnyway = { dashboardViewModel.quitAnyway() },
        )
    }

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
                    onClose = { dashboardViewModel.requestClose() },
                    onMinimize = onMinimizeWindow,
                    onMaximize = onMaximizeWindow,
                )

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    // Medium is a real bucket, not a synonym for Expanded: it keeps the
                    // sidebar but the screens drop their fixed-width secondary panels, since
                    // 600–840dp cannot afford a 220dp sidebar and a 280dp inspector at once.
                    val windowSize = getActiveWindowSize(maxWidth, layoutOverride)
                    if (showOnboarding) {
                        // Inside BoxWithConstraints rather than over the whole window, so the
                        // top bar's close and minimize controls stay reachable — a takeover
                        // that covers them leaves a desktop user unable to quit (D-close).
                        OnboardingScreen(
                            // Finishing and skipping both persist: someone who dismissed it
                            // deliberately must not meet it again on the next launch.
                            onFinish = { onboardingMemory.markCompleted(); showOnboarding = false },
                            onSkip = { onboardingMemory.markCompleted(); showOnboarding = false },
                            windowSize = windowSize,
                        )
                    } else if (windowSize == WindowSize.Compact) {
                        PhoneRoot(
                            // One copy of "which screen am I on", owned here. Both roots
                            // used to hold their own, so crossing the width boundary — or
                            // switching the Layout setting, which lives *in* Settings —
                            // silently dropped you back on Dashboard.
                            currentScreen = currentScreen,
                            onNavigate = { currentScreen = it },
                            dashboardViewModel = dashboardViewModel,
                            hasExifTool = hasExifTool,
                            hasFFmpeg = hasFFmpeg,
                            onVerifyDependencies = onVerifyDependencies,
                            themeMode = themeMode,
                            onThemeModeChange = { themeMode = it; saveThemeModePreference(it) },
                            layoutOverride = layoutOverride,
                            onLayoutOverrideChange = { layoutOverride = it; saveLayoutOverride(it) },
                            onShowOnboarding = { showOnboarding = true },
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
                                        hasExifTool = hasExifTool,
                                        hasFFmpeg = hasFFmpeg,
                                        windowSize = windowSize,
                                    )
                                    Screen.Library -> LibraryScreen(
                                        downloadFolder = dashboardViewModel.downloadFolder,
                                        onOpenFolder = dashboardViewModel::pickOutputFolder,
                                        folderChangeable = dashboardViewModel.outputFolderChangeable,
                                        windowSize = windowSize,
                                        favoriteOverrides = dashboardViewModel.favoriteOverrides,
                                        onToggleFavorite = { item, favorited ->
                                            dashboardViewModel.setFavorite(item.id, favorited)
                                        },
                                        onFavoritesScanned = dashboardViewModel::reconcileFavorites,
                                    )
                                    Screen.Settings -> SettingsScreen(
                                        hasExifTool = hasExifTool,
                                        hasFFmpeg = hasFFmpeg,
                                        onVerifyDependencies = onVerifyDependencies,
                                        downloadFolder = dashboardViewModel.downloadFolder,
                                        onResetIndex = { scope.launch { dashboardViewModel.resetVaultIndex() } },
                                        onEditOutputPath = { dashboardViewModel.pickOutputFolder() },
                                        outputFolderChangeable = dashboardViewModel.outputFolderChangeable,
                                        resetOutcome = dashboardViewModel.lastIndexReset,
                                        themeMode = themeMode,
                                        onThemeModeChange = { themeMode = it; saveThemeModePreference(it) },
                                        layoutOverride = layoutOverride,
                                        onLayoutOverrideChange = { layoutOverride = it; saveLayoutOverride(it) },
                                        // Replaying never clears the completed flag, so it is
                                        // a one-off view rather than a re-arm of first launch.
                                        onShowOnboarding = { showOnboarding = true },
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
