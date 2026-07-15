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
3. On Linux, install FFmpeg with your package manager (e.g. `sudo apt install ffmpeg`); on macOS/Windows both tools are bundled
4. On the **Dashboard**, choose your Snapchat ZIP(s) and an output folder
5. Configure pipeline options and click **Start Download**

## Import Modes

- **ZIP Import** (recommended) — point SnapVault at the `mydata~*.zip` archives from your export; media is extracted, tagged, and combined directly from the archives.
- **Legacy (HTML/JSON)** — for the older link-based export (`memories_history.html`/`.json` with download links): downloads every memory, extracts overlay archives, and writes the full capture date *and time*, plus GPS coordinates where your export includes them. Note that download links expire about 7 days after the export is generated.

## Pipeline Options

| Option | What it does |
|---|---|
| **Download Memories** | (Legacy mode) Downloads every memory from the links in your history file |
| **Write Date Metadata** | Tags each file with its Snapchat capture date via ExifTool (legacy mode also writes time-of-day and GPS where available) |
| **Merge Video Overlays** | Combines `-main` + `-overlay` pairs (photos and videos) into a single composited output, using GPU-accelerated encoding when the hardware supports it (NVENC/VideoToolbox/QSV/VAAPI/AMF, verified by a runtime probe with automatic software fallback) |
| **Clean Duplicate Files** | Removes byte-identical duplicates, keeping the earliest-dated copy; enable the dry-run toggle to preview deletions first |

> **Why is date metadata in ZIP mode date-only (no time or GPS)?** Snapchat's Memories export provides capture times and locations in `memories_history.json`, but those records contain no identifier that matches the exported filenames, so they cannot be reliably matched to files. See the note in the repo about contacting Snap regarding the export format.

## System Requirements

- macOS 12+, Windows 10+, or Ubuntu 20.04+
- FFmpeg and ExifTool — bundled with the app on macOS and Windows. On Linux, ExifTool is bundled (requires system Perl); install FFmpeg via your package manager (`sudo apt install ffmpeg`)

## Building from Source

Requires JDK 21.

```bash
# Run in development (implies debug mode: import capped at 2,500 items)
./gradlew :composeApp:run

# Build a native installer for the current OS (release/ProGuard variant)
./gradlew packageReleaseDistributionForCurrentOS
```

Debug mode is opt-in everywhere else via `-PisDebug=true`. Third-party license notices for the bundled tools ship inside the app (`THIRD_PARTY_LICENSES.md`).

The app version is set in `gradle.properties` (`app.version`). Releases are cut manually via the **Release** workflow (Actions → Release → Run workflow), which bumps the version, tags, runs the desktop test suite, and publishes installers.

## Legacy Python Scripts

The original Python-based CLI scripts are preserved in [`legacy/`](legacy/) for reference. They are not actively maintained.

## Credits

Forked from [ManuelPuchner/snapchat-memories-downloader](https://github.com/ManuelPuchner/snapchat-memories-downloader). Thanks to Manuel and [Nick](https://github.com/nrc2358) for the original implementation.

## License

[MIT](LICENSE)
