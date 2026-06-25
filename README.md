# SnapVault

**SnapVault** is an open-source desktop app for downloading, organizing, and processing your Snapchat memories. Works entirely offline after your export is ready — no Python, no CLI, no manual setup.

Built with Kotlin Multiplatform + Compose Desktop. Ships as a native installer for macOS, Windows, and Linux.

## Getting Your Snapchat Data

1. Go to [accounts.snapchat.com](https://accounts.snapchat.com) → **My Data**
2. Select **Export your Memories** → **Request Only Memories** → **All Time**
3. Wait for the download link in your email (usually a few hours)
4. Download the ZIP file(s) from the email — **do not extract them**

## Quickstart

1. **Download** the latest release for your OS from the [Releases](../../releases) page
2. **Install** the `.dmg` (macOS), `.msi` (Windows), or `.deb` (Linux)
3. Open **Settings → Auto-Setup Binaries** to install FFmpeg and ExifTool
4. On the **Dashboard**, choose your Snapchat ZIP(s) and an output folder
5. Configure pipeline options and click **Start Download**

## Pipeline Options

| Option | What it does |
|---|---|
| **Download Memories** | Extracts and downloads photos/videos from your Snapchat export ZIP |
| **Write Date Metadata** | Tags each file with its original Snapchat creation date via ExifTool |
| **Merge Video Overlays** | Combines `-main` + `-overlay` file pairs into a single composited output |
| **Clean Duplicate Files** | Removes exact duplicate files from the output folder |

## System Requirements

- macOS 12+, Windows 10+, or Ubuntu 20.04+
- FFmpeg and ExifTool — installed automatically via **Settings → Auto-Setup Binaries**

## Building from Source

Requires JDK 21.

```bash
# Run in development
./gradlew :composeApp:run

# Build a native installer for the current OS
./gradlew packageDistributionForCurrentOS
```

The app version is set in `gradle.properties` (`app.version`). CI automatically bumps and tags on every push to `main`.

## Legacy Python Scripts

The original Python-based CLI scripts are preserved in [`legacy/`](legacy/) for reference. They are not actively maintained.

## Credits

Forked from [ManuelPuchner/snapchat-memories-downloader](https://github.com/ManuelPuchner/snapchat-memories-downloader). Thanks to Manuel and [Nick](https://github.com/nrc2358) for the original implementation.

## License

[MIT](LICENSE)
