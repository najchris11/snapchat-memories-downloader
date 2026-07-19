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
   > **Note:** releases are not yet code-signed. On macOS, Gatekeeper will warn that the app is from an unidentified developer — right-click the app → **Open** → **Open** (or run `xattr -d com.apple.quarantine /Applications/SnapVault.app`). On Windows, SmartScreen may show "Windows protected your PC" — click **More info** → **Run anyway**. See [docs/CODE_SIGNING.md](docs/CODE_SIGNING.md) for the signing roadmap.
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
| **Write Date Metadata** | Tags each file with its Snapchat capture date via ExifTool. In ZIP mode, precise time + GPS matching (on by default; can be turned off) also recovers time-of-day and GPS from `memories_history.json`. Legacy mode writes time-of-day and GPS where the history file provides them |
| **Merge Video Overlays** | Combines `-main` + `-overlay` pairs (photos and videos) into a single composited output, using GPU-accelerated encoding when the hardware supports it (NVENC/VideoToolbox/QSV/VAAPI/AMF, verified by a runtime probe with automatic software fallback) |
| **Clean Duplicate Files** | Removes byte-identical duplicates, keeping the earliest-dated copy; enable the dry-run toggle to preview deletions first |

> **How does ZIP mode recover time-of-day and GPS?** Snapchat's filenames only carry the capture date, but each file's exact capture timestamp is stored in the ZIP archive's extended-timestamp metadata. The matcher (on by default) pairs that timestamp second-for-second against `memories_history.json`, recovering full time-of-day and GPS. It never guesses: when two records share the same timestamp and their locations disagree, GPS is omitted for those files, and files without a timestamp match fall back to date-only tags. The **Experimental ZIP metadata matching** toggle on the Dashboard can be switched off to force conservative date-only tagging for every file.

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

[GPL-3.0](LICENSE)
