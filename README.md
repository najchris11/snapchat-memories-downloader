# Snapchat Memories Downloader
Since snapchat wants you to pay for more than 5gb of snapchat memories, I made a script to download all your memories since the version snapchat provided has a bug where it says 100% is downloaded but in reality it didn't download anything (at least in my case)

## Credits

This is a fork of [ManuelPuchner/snapchat-memories-downloader](https://github.com/ManuelPuchner/snapchat-memories-downloader) with improvements for zero-setup usage and test mode. Thanks to Manuel and [Nick](https://github.com/nrc2358) for the original implementation! 🙏

# How to Run

## GUI (Recommended)

The GUI provides a one‑click setup and an easy workflow runner.

```bash
cd gui
npm install
npm run electron-dev
```

- Click "Setup Environment" to install all required tools.
- Then select your `memories_history.html` and click "Start Download".

### Requirements (GUI enforces these)
- Python 3.10+ with `pip`
- `exiftool` (required for writing GPS metadata to files)
- `ffmpeg` (required for combining video overlays)

### OS‑Specific Setup
- **macOS:** The GUI runs `installer.sh` which uses Homebrew to install Python, ExifTool, and FFmpeg, sets up `.venv`, and installs `requirements.txt`.
- **Windows:** The GUI runs `installer.ps1` which uses `winget` to install Python, ExifTool, and FFmpeg, sets up `.venv`, and installs `requirements.txt`.
- **Linux:** The GUI shows a modal with exact commands. Install the required packages manually:
	- `sudo apt update && sudo apt install python3 python3-pip python3-venv exiftool ffmpeg`
	- `python3 -m venv .venv && source .venv/bin/activate`
	- `pip install -r requirements.txt`

The installers prepare the environment only; the GUI handles running workflows.

### Packaged App: Bundled Runtime (No User Setup)

The packaged Electron app can bundle the Python runtime, pip dependencies, and
native tools so users do not need to install anything manually.

Expected layout in the repo before running `npm run electron-build`:

```
runtime/
	python/
		...full Python distribution with site-packages...
	tools/
		darwin/
			exiftool
			ffmpeg
		win32/
			exiftool.exe
			ffmpeg.exe
		linux/
			exiftool
			ffmpeg
```

Notes:
- Put a full Python runtime in `runtime/python` and pre-install `requirements.txt` into it.
- Place OS-specific `exiftool` and `ffmpeg` binaries in `runtime/tools/<platform>`.
- The packaged app will prefer these bundled binaries and fall back to system installs only in dev.

CI prepares the `runtime/` directory during release builds using the scripts in `scripts/`.
The bundled runtime is not committed to git; it is assembled on each release runner.

Optional checksum validation:
- Set `PYTHON_SHA256`, `EXIFTOOL_SHA256`, and `FFMPEG_SHA256` in the build environment.
- If provided, the runtime scripts will validate each download and fail on mismatch.
	- ExifTool 13.58 checksums (from https://exiftool.org/checksums-13.58.txt):
		- macOS/Linux tarball (`Image-ExifTool-13.58.tar.gz`): `c84fb6b613a480a638225d44979bf44cd2f91c92b79f4d2aa43773c89fa4199e`
		- Windows zip (`exiftool-13.58_64.zip`): `fd3b47a01e6ffc6160f2d5fde5ff0c003f6c4c2ba85eee1ce8928ccb51fa3e6`

## CLI (Alternative)

Manual setup for CLI usage (outside the GUI):

1) Create a Python venv:
```bash
python3 -m venv .venv
```

2) Activate the venv:
```bash
# macOS/Linux
source .venv/bin/activate

# Windows
.\.venv\Scripts\Activate.ps1
```

3) Install Python dependencies:
```bash
pip install -r requirements.txt
```

4) Install required system tools:
```bash
# macOS
brew install exiftool ffmpeg

# Linux
sudo apt install exiftool ffmpeg

# Windows: install via winget or use Chocolatey/manual installers
#   winget install -e --id PhilHarvey.ExifTool
#   winget install -e --id FFmpeg.FFmpeg
```

## Request & Download Data

1. Go to [https://accounts.snapchat.com](https://accounts.snapchat.com)
2. Click **My Data**
3. Select **Export your Memories** → **Request Only Memories** → **All Time**
4. Check your email for the download link
5. Download and extract the HTML file
6. Place `memories_history.html` in the project root

## Run the Orchestrator (CLI)

```bash
python3 run_all.py              # Interactive menu
python3 run_all.py --full       # Run all steps sequentially
python3 run_all.py --test 10    # Download only 10 items first (sanity check)
```

Or use individual scripts:
- `python3 snapchat-downloader.py` – Download memories only
- `python3 metadata.py` – Write GPS metadata
- `python3 combine_overlays.py` – Merge overlays (set `DRY_RUN=False` first)
- `python3 delete-dupes.py` – Remove duplicates (set `DRY_RUN=False` first)

### Parallel Workers

Pass `--workers=N` to any script to control concurrency:
```bash
python3 combine_overlays.py --workers=8
python3 metadata.py --workers=8
python3 delete-dupes.py --workers=8
```

## Post-Processing

Correct file timestamps to match creation date (macOS):
```bash
mdimport -r snapchat_memories/
exiftool "-FileCreateDate<CreateDate" "-FileModifyDate<CreateDate" -ext mp4 -r snapchat_memories/
exiftool "-FileCreateDate<CreateDate" "-FileModifyDate<CreateDate" -ext jpg -r snapchat_memories/
```
