# SnapVault KMP — Dev Plan

> Branch: `feature/kmp-modernization`  
> Last updated: 2026-06-03

---

## Overview

Next session priorities, in rough execution order. Stitch redesigns should be generated first since they inform implementation decisions.

---

## 0. Stitch Redesigns (do first — blocks UI decisions)

Generate updated Stitch screens for **both mobile and desktop** before touching code. The current Stitch designs predate several of these changes (no setup card, simplified dashboard, settings has workers slider + theme toggle, etc.).

Screens to regenerate:
- **Desktop Dashboard** — no setup card, new progress UX (status ring + expandable logs), source/destination + pipeline toggles only
- **Desktop Settings** — workers slider promoted here, theme toggle, dependencies section, concurrency, advanced tools
- **Mobile Dashboard** — same layout priorities adapted for mobile
- **Mobile Settings** — same

Use `mcp__stitch__edit_screens` or `mcp__stitch__generate_screen_from_text` against project `6933190367428174753`.

---

## 1. Remove Native Window Title Bar

**Why:** The OS-provided title bar with the window title text looks out of place against the custom top bar.

**Approach:**
```kotlin
// Main.kt
Window(
    onCloseRequest = ::exitApplication,
    undecorated = true,          // removes native chrome
    transparent = false,
    state = windowState,
    icon = painterResource(Res.drawable.ic_launcher)
) { ... }
```

The custom top bar in `App.kt` then becomes the only chrome. It needs:
1. `WindowDraggableArea { ... }` wrapping the top bar so the user can drag the window
2. Window control buttons (close / minimize / maximize) at the top-right — or top-left macOS style
3. Import `androidx.compose.ui.window.WindowDraggableArea`

Platform note: On macOS `undecorated = true` removes the traffic lights. The custom controls should match platform convention or use a universal set. A simple `×  −  ▢` set in the top bar is sufficient for now.

**Files:** `Main.kt`, `App.kt`

---

## 2. Sidebar Cleanup

Remove:
- The entire logo/name/tagline block at the top of the sidebar (redundant — the top bar already has branding)
- The "Start Sync" button at the bottom (the primary action lives on the Dashboard)

The sidebar should be navigation only: three nav items + the status chip at the bottom.

**File:** `App.kt` — delete the logo `Row` and the `Button` composable at the bottom of the sidebar column.

---

## 3. Dark Mode / Light Mode

**Add a light theme** and a persisted preference toggle in Settings.

### Theme.kt changes
Add a `SnapVaultLightColorScheme` using `lightColorScheme(...)`. Key light-mode token overrides:
- `background` → `#F4F6FB` (cool off-white)  
- `surface` → `#FFFFFF`
- `surfaceVariant` → `#E8ECF4`
- `onBackground` / `onSurface` → `#0D1525` (dark navy, not pure black)
- `primary` → `#6D3BD7` (slightly deeper purple so it reads on light bg)
- Keep `ElectricPurple` as-is for icons/accents

### App.kt changes
```kotlin
var isDarkMode by remember { mutableStateOf(true) }

SnapVaultTheme(darkMode = isDarkMode) { ... }
```

Update `SnapVaultTheme` to accept `darkMode: Boolean` and branch on `colorScheme`.

### Persistence
Use `java.util.prefs.Preferences` on Desktop (platform-specific via `expect`/`actual`):
```kotlin
// desktopMain
actual fun saveThemePreference(dark: Boolean) {
    Preferences.userRoot().node("snapvault").putBoolean("darkMode", dark)
}
actual fun loadThemePreference(): Boolean =
    Preferences.userRoot().node("snapvault").getBoolean("darkMode", true)
```

### Settings page
Add a `Switch` row for "Dark Mode" with `Icons.Outlined.DarkMode` / `Icons.Outlined.LightMode`.

### Readability audit while you're in there
Several `onSurfaceVariant.copy(alpha = 0.35f)` values are too dim in both modes. Minimum alpha for readable secondary text should be **0.55f** in dark mode, **0.6f** in light mode. Do a search-and-replace pass in all three screen files.

**Files:** `Theme.kt`, `App.kt`, `SettingsScreen.kt`, `DashboardScreen.kt`, `LibraryScreen.kt`

---

## 4. Dashboard Simplification

### Remove the Setup card
Delete the `ControlCard { ... }` block that contains `dash_setup_title`, `dash_setup_subtitle`, and the Configure button. The assumption is that bundled binaries cover the normal case; if something is missing the user navigates to Settings themselves.

### Remove Workers slider
Delete the `HorizontalDivider` + workers `Slider` block from `DashboardScreen.kt`. It now lives only in `SettingsScreen.kt`.

Also remove the `workers` state variable from `DashboardScreen` and thread the value in from `App.kt` state (or from a `Settings` data class) so `onStartSync` still receives the right concurrency.

### New progress UX
Replace the current terminal-first layout (right column = terminal + small progress card below) with a **status-first** layout:

**Right column, top:** A clean status card showing:
- Step indicator (the stepper row — keep as-is)
- A larger, centered progress ring (`CircularProgressIndicator`) with the percentage in the middle
- One-line status text below it ("Downloading 42 of 214 files…")
- Speed + ETA chips side by side

**Right column, bottom:** An expandable "View Logs" section — collapsed by default, showing only the last log line as a preview. When expanded, shows the full terminal view (existing `LazyColumn` + blinking cursor). Use `AnimatedVisibility` for the expand/collapse transition.

```
┌─ right column ──────────────────────┐
│  [Stepper: Setup → Syncing → …]     │
│                                     │
│         ╭──────────╮                │
│         │   42%    │   ← ring       │
│         ╰──────────╯                │
│    Downloading 42 of 214 files…     │
│    [SPEED: 4.2 MB/s]  [ETA: 1m 32s] │
│                                     │
│  ▸ View Logs  (last: [DL] foo.jpg)  │  ← collapsed
│    ┌─ terminal ──────────────────┐  │  ← expanded
│    │ [INFO] Starting pipeline…   │  │
│    └────────────────────────────┘   │
└─────────────────────────────────────┘
```

**Files:** `DashboardScreen.kt`, `App.kt` (thread workers from settings state)

---

## 5. Settings Page ("Settings", not "Environment")

Rename the nav label back to "Settings":
```xml
<!-- strings.xml -->
<string name="nav_settings">Settings</string>
<string name="settings_title">Settings</string>
```

### Promote Workers slider here
Add a `Parallel Workers` slider (1–16) to the General Settings card in `SettingsScreen`. The value must be lifted to App-level state so the Dashboard can consume it.

**Files:** `strings.xml`, `SettingsScreen.kt`, `App.kt`

---

## 6. App Version from Gradle

**Goal:** Single source of truth in `gradle.properties`; the app reads it at runtime.

### gradle.properties
```properties
app.version=0.1-alpha
```

### build.gradle.kts
```kotlin
val appVersion = project.findProperty("app.version") as String

// Also update versionName/packageVersion to match
android { defaultConfig { versionName = appVersion } }
compose.desktop { application { nativeDistributions { packageVersion = "0.1.0" } } }

// Generate AppBuildConfig.kt into commonMain sources
val generateBuildConfig by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/buildConfig/commonMain/kotlin")
    outputs.dir(outDir)
    inputs.property("version", appVersion)
    inputs.property("isDebug", gradle.startParameter.taskNames.none { "release" in it.lowercase() || "Dist" in it })
    doLast {
        val isDebug = gradle.startParameter.taskNames.none { "release" in it.lowercase() || "Dist" in it }
        outDir.get().asFile.mkdirs()
        file("${outDir.get()}/AppBuildConfig.kt").writeText("""
            package com.najdev.snapvault
            object AppBuildConfig {
                const val VERSION = "$appVersion"
                const val IS_DEBUG = $isDebug
            }
        """.trimIndent())
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin
    .srcDir(generateBuildConfig.map { it.outputs.files })
```

### Usage in the app
```kotlin
// strings.xml: <string name="app_version">%s</string>  (already parameterized)
// In composables:
AppBuildConfig.VERSION          // "0.1-alpha"
AppBuildConfig.IS_DEBUG         // true when running via ./gradlew runDesktop
```

Replace the hardcoded `Res.string.app_version` (`"v2.4.0"`) with `AppBuildConfig.VERSION`.

**Files:** `gradle.properties`, `build.gradle.kts`, `App.kt`, `SettingsScreen.kt`

---

## 7. Debug Build Mode + Badge

### What debug mode does
When `AppBuildConfig.IS_DEBUG == true` (i.e., `./gradlew runDesktop`):
- `DownloadEngine.downloadAll()` only processes the first **5 items**
- Log a clear `[DEBUG] Limiting to 5 items (debug mode)` line in the terminal
- The limit is enforced in `App.kt` by slicing `items.take(if (AppBuildConfig.IS_DEBUG) 5 else Int.MAX_VALUE)` before passing to `downloadAll`

### Debug badge in top bar
Add a small chip in the top bar, right of the version chip, only when `IS_DEBUG`:
```kotlin
if (AppBuildConfig.IS_DEBUG) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFFBBF24).copy(alpha = 0.15f))  // amber
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text("DEBUG", fontSize = 9.sp, color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
    }
}
```

### Remove test-run folder
```bash
rm -rf test-run/
```
Update `.gitignore` if it references `test-run/`.

**Files:** `App.kt`, `DownloadEngine.kt` (or handle in App.kt), `build.gradle.kts`

---

## 8. Real Library Page

Currently `LibraryScreen` renders 8 hardcoded `LibraryItem` objects. Replace with actual file scanning.

### Data flow
`App.kt` passes `downloadFolder: String?` to `LibraryScreen`. When non-null, scan the folder for media files.

### File scanning (Desktop)
```kotlin
// commonMain expect
expect fun scanMediaFiles(folderPath: String): List<LibraryItem>

// desktopMain actual
actual fun scanMediaFiles(folderPath: String): List<LibraryItem> {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return emptyList()
    val mediaExtensions = setOf("jpg", "jpeg", "png", "mp4", "mov", "gif")
    return folder.listFiles()
        ?.filter { it.extension.lowercase() in mediaExtensions }
        ?.sortedByDescending { it.lastModified() }
        ?.map { file ->
            LibraryItem(
                id = file.name,
                date = formatFileDate(file.lastModified()),
                title = file.nameWithoutExtension,
                type = if (file.extension.lowercase() in setOf("mp4", "mov")) "video" else "photo",
                duration = null,   // TODO: read from metadata
                hasGps = false,    // TODO: read from downloaded_files.json
                hasOverlay = false
            )
        } ?: emptyList()
}
```

### Thumbnail loading (Desktop)
```kotlin
// desktopMain
fun loadThumbnail(path: String): ImageBitmap? = runCatching {
    File(path).inputStream().use { loadImageBitmap(it) }
}.getOrNull()
```

Use `AsyncImage`-style loading with `produceState` in `MediaCard` — show a placeholder while loading, swap to real image when ready.

### Wiring
- Add `downloadFolder: String?` and `onOpenFolder: () -> Unit` params to `LibraryScreen`
- Pass real scanned items into the grid
- Show `lib_empty_state` string when folder is null or empty

**Files:** `LibraryScreen.kt`, `App.kt`, new `expect`/`actual` `MediaScanner.kt`

---

## Execution Order

| # | Task | Blocks |
|---|------|--------|
| 0 | Stitch redesigns (Desktop + Mobile) | UI decisions for 4, 5 |
| 1 | Gradle version + `AppBuildConfig` generation | 6, 7 |
| 2 | Remove native title bar + `WindowDraggableArea` | — |
| 3 | Sidebar cleanup (remove logo block + Start Sync) | — |
| 4 | Rename Settings nav label | — |
| 5 | Dark/Light mode + readability audit | — |
| 6 | Dashboard simplification (remove Setup card + workers slider + new progress UX) | Stitch designs |
| 7 | Settings page (add workers slider, theme toggle) | 5 |
| 8 | Debug build mode + badge + remove test-run | 1 |
| 9 | Real Library page (file scanning + thumbnails) | — |

---

## Open Questions

- ~~**Custom window controls styling:**~~ **Decided:** Platform-universal — right side, monochrome ×  −  ▢ buttons. No macOS traffic lights.
- **Theme persistence:** `java.util.prefs.Preferences` for Desktop is fine. Android uses `DataStore`. iOS TBD. Do we need a shared `Settings` repository in commonMain?
- **Thumbnail performance:** Real thumbnails for hundreds of images need lazy loading + a disk cache. Is that in scope now, or do we ship with extension-based placeholders first?
- **`downloaded_files.json` location:** The manifest is written to cwd by the old Python scripts. The KMP `DownloadEngine` writes it to... check `DownloadEngine.kt`. Should it live inside the output folder so `scanMediaFiles` can cross-reference GPS/overlay data?
