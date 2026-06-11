# SnapVault — Snapchat Memories Downloader

A Kotlin Multiplatform app (macOS · Android · iOS) for exporting, organizing, and enriching your Snapchat memories with GPS metadata and timestamp data from your Snapchat data export.

> **Why?** Snapchat's own download tool is notoriously broken. SnapVault reads your data export ZIP(s), extracts the media files, writes correct EXIF timestamps and GPS coordinates, and combines video overlays — all without touching Snapchat's servers.

---

## Platforms

| Platform | Status | How to run |
|----------|--------|-----------|
| macOS (Desktop) | ✅ Full support | `./gradlew :composeApp:run` |
| Android | ✅ Supported | `./gradlew :composeApp:runAndroid` |
| iOS Simulator | ✅ Supported | `./gradlew :composeApp:runIosSimulator` |

---

## Getting Your Snapchat Export

Snapchat now exports multiple 2 GB ZIP files instead of a single HTML with CDN links.

1. Go to [accounts.snapchat.com](https://accounts.snapchat.com) → **My Data**
2. Select **Export your Memories** → **Request Only Memories** → **All Time**
3. Check your email — the download link arrives within 24 hours
4. Download all ZIP files (named `mydata~{timestamp}-{N}.zip`)

The ZIPs contain:
- `memories/memories.html` — local media file references
- `YYYY-MM-DD_UUID-{main|overlay}.{ext}` — media files
- `html/memories_history.html` — GPS + timestamp metadata
- `json/memories_history.json` — JSON metadata fallback

---

## Running the App

### Prerequisites

- **JDK 17+** (Android Studio's bundled JBR is configured automatically via `gradle.properties`)
- **Android Studio** or any IDE with KMP support (for Android dev)
- **Xcode 15+** (for iOS builds, macOS only)
- **Android SDK** (installed via Android Studio)

### macOS Desktop

```bash
./gradlew :composeApp:run
```

Produces a full desktop app with a sidebar, dependency management (ExifTool + FFmpeg auto-bundled), and full pipeline support.

To build a distributable DMG:
```bash
./gradlew :composeApp:createDistributable
```

### Android

```bash
./gradlew :composeApp:runAndroid
```

This builds the debug APK, installs it on the connected device or running emulator, and launches the app. Requires `adb` on your `PATH` and a connected device or a running emulator.

To only build the APK:
```bash
./gradlew :composeApp:assembleDebug
# Output: composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

### iOS Simulator

```bash
./gradlew :composeApp:runIosSimulator
```

This builds the Kotlin framework via `embedAndSignAppleFrameworkForXcode`, builds the `iosApp` Xcode project, boots a simulator if none is running, installs, and launches the app.

Alternatively, open `iosApp/iosApp.xcodeproj` in Xcode and press **Run** (`⌘R`).

> **Note:** The Xcode project links `ComposeApp.framework` via `OTHER_LDFLAGS`. The framework is built by a Run Script phase that calls `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`. No manual framework copy is needed.

---

## App Architecture

```
composeApp/
  src/
    commonMain/         ← Shared UI + business logic (Compose Multiplatform)
      ui/               ← DashboardScreen, LibraryScreen, SettingsScreen, PhoneRoot
      viewmodel/        ← DashboardViewModel (pipeline state)
      downloader/       ← ZipPipelineRunner, DownloadEngine, Deduplicator
      parser/           ← ZipImportParser, ZipHistParser, ZipJsonParser, MetadataCorrelator
      metadata/         ← MediaProcessor (expect/actual GPS + date writing)
      model/            ← FileMeta, PipelineState, MemoryItem
    androidMain/        ← Android actuals (ExifInterface, pickers, MediaScanner stub)
    iosMain/            ← iOS actuals (NSUserDefaults, pickers stub, AVFoundation stubs)
    desktopMain/        ← Desktop actuals (ExifTool, FFmpeg, Skia thumbnails, full pipeline)
iosApp/
  iosApp.xcodeproj/     ← Xcode project (wraps ComposeApp.framework)
  iosApp/
    iOSApp.swift        ← SwiftUI @main entry point
    ContentView.swift   ← UIViewControllerRepresentable wrapping MainViewController
```

### Responsive layout

The app adapts automatically based on window width:

| Width | Layout |
|-------|--------|
| < 600 dp | Mobile: bottom navigation bar (`PhoneRoot`) |
| 600–839 dp | Medium desktop layout |
| ≥ 840 dp | Expanded desktop: sidebar + content |

You can override the layout via **Settings → Layout Override**.

---

## Pipeline (ZIP Import Mode)

1. **Select ZIP(s)** — single file or a folder of `mydata~*.zip` files
2. **Select output folder** — where extracted media lands
3. **Run** — the pipeline:
   - Extracts media from all ZIPs (parallel workers)
   - Correlates each file against `memories_history.html`/`.json` for GPS + exact timestamp
   - Writes EXIF metadata to extracted files
   - Combines video + overlay pairs into a single file
   - Deduplicates identical files
   - Saves a `vault_index.json` and `vault_pipeline.json` for resume support

---

## Development

### Gradle tasks

| Task | Description |
|------|-------------|
| `:composeApp:run` | Run the desktop app |
| `:composeApp:runCli` | Run the CLI pipeline (headless) |
| `:composeApp:runAndroid` | Install + launch on Android device/emulator |
| `:composeApp:runIosSimulator` | Build + run in iOS Simulator |
| `:composeApp:assembleDebug` | Build Android debug APK |
| `:composeApp:installDebug` | Install Android debug APK |
| `:composeApp:embedAndSignAppleFrameworkForXcode` | Build iOS framework (called by Xcode) |
| `:composeApp:check` | Run all tests |
| `:composeApp:createDistributable` | Build macOS .app bundle |

### Running tests

```bash
./gradlew :composeApp:check
```

Tests live in `composeApp/src/commonTest/` and cover the parsers, deduplicator, and download engine.

---

## Legacy (Python / Electron)

The original Python CLI and Electron GUI are preserved in the `legacy/` directory for reference. They are no longer maintained. The KMP app supersedes them with a cross-platform UI, built-in dependency bundling, and resume support.

---

## Credits

Forked from [ManuelPuchner/snapchat-memories-downloader](https://github.com/ManuelPuchner/snapchat-memories-downloader). Thanks to Manuel and [Nick](https://github.com/nrc2358) for the original implementation.
