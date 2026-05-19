# SnapVault Downloader — Resume & Pick-Up-Where-You-Left-Off

## Current Session Updates (2026-05-18)

### Runtime Bundling + ExifTool Fixes

- Updated ExifTool downloads to SourceForge for macOS/Linux/Windows.
  - macOS/Linux now pull `Image-ExifTool-13.58.tar.gz` and install a wrapper that runs `exiftool` via system Perl from a bundled `exiftool-dist/` folder.
  - Windows now pulls `exiftool-13.58_64.zip` and copies both `exiftool.exe` and `exiftool_files`.
- Bumped ExifTool version to 13.58 in runtime prep scripts.
- Added ExifTool 13.58 SHA256 checksums directly to CI for each OS and documented them in README.

### Test Results

- macOS bundled runtime was rebuilt and a smoke test succeeded:
  - `exiftool -ver` returned 13.58
  - `ffmpeg -version` ran successfully via bundled PATH
- Running the Linux prep script on macOS fails (expected) because it downloads a Linux Python binary.
  - If testing Linux, run `scripts/prepare-runtime-linux.sh` on a Linux host/runner.

### Files Touched

- `scripts/prepare-runtime-macos.sh`
- `scripts/prepare-runtime-linux.sh`
- `scripts/prepare-runtime-windows.ps1`
- `.github/workflows/release.yml`
- `README.md`

## Problem

The current pipeline is a strict 4-step sequence:

```
memories_history.html → [1] Download → [2] GPS Metadata → [3] Combine Overlays → [4] Deduplicate
```

If a user stops mid-pipeline (crash, timeout, closed laptop) or re-downloads their Snapchat data export later, they currently have **no way** to resume from a specific stage. They must either:
- Re-run the entire pipeline from scratch, or
- Manually invoke individual Python scripts with CLI flags — which the GUI doesn't expose.

Additionally, users who already have **partially processed outputs** (e.g. a folder of already-downloaded ZIPs, or files already with GPS data) can't point the tool at those existing artifacts and skip completed stages.

---

## Goal

Let users **point to existing files or folders** at any stage of the pipeline, so they can resume or skip steps intelligently:

| User Has... | Should Be Able To... |
|---|---|
| `memories_history.html` only | Start fresh (current behavior) |
| A folder of already-downloaded media (from a previous run or another tool) | Skip Step 1, run Steps 2-4 on that folder |
| `downloaded_files.json` + media folder | Skip Step 1, use the JSON manifest for metadata mapping |
| A folder with unmerged overlay subfolders | Skip Steps 1-2, run Steps 3-4 |
| A ZIP of Snapchat export data | Extract → detect stage → resume |
| A completed output folder (just wants dedup) | Skip Steps 1-3, run Step 4 only |

---

## Pipeline Stage Artifacts

Each stage produces specific files. Detection of these artifacts tells us what's already been done:

| Stage | Input | Output Artifacts | Detection Signal |
|---|---|---|---|
| **1. Download** | `memories_history.html` | Media files in output dir + `downloaded_files.json` + `download_errors.json` | `downloaded_files.json` exists AND has entries matching files on disk |
| **2. GPS Metadata** | `downloaded_files.json` + `memories_history.html` | `metadata.json` + updated `downloaded_files.json` (with `metadata_written: true` flags) | `metadata.json` exists OR entries in `downloaded_files.json` have `metadata_written: true` |
| **3. Combine Overlays** | Media folder with `*-main.*` / `*-overlay.*` subfolders | Merged files replace subfolders | No subfolders containing `*-main.*` + `*-overlay.*` pairs remain |
| **4. Deduplicate** | Media folder | Duplicate files removed | (Idempotent — safe to re-run) |

---

## Proposed Changes

### 1. GUI: "Resume" Mode in Dashboard

**File: `gui/components/pages/DashboardPage.tsx`**

Add a **"Resume from Existing"** section to the Source & Destination card. When a user points to an output folder, auto-detect which stages are already complete:

```
┌─ Source & Destination ──────────────────────────────┐
│                                                      │
│  History Data       [memories_history.html] [Browse] │
│  Output Folder      [~/Downloads/snap_mem]  [Select] │
│                                                      │
│  ── Resume Detection ──────────────────────────────  │
│  ✅ Step 1: Download     214 files found             │
│  ✅ Step 2: GPS Metadata metadata.json present       │
│  ⬜ Step 3: Overlays     12 folders pending          │
│  ⬜ Step 4: Dedup        Not yet run                 │
│                                                      │
│  [Start from Step 3 →]                               │
└──────────────────────────────────────────────────────┘
```

**Implementation:**
- When the user selects an output folder, send an IPC call `detect-pipeline-state` with the folder path
- Electron main.js scans the folder for artifacts (see detection table above)
- Returns a status object per step:
  ```ts
  interface PipelineState {
    download: { done: boolean; fileCount: number; hasManifest: boolean };
    metadata: { done: boolean; gpsWrittenCount: number; hasMetadataJson: boolean };
    overlays: { done: boolean; pendingFolders: number };
    dedupe:   { done: boolean };  // always false (idempotent)
    suggestedStartStep: 1 | 2 | 3 | 4;
  }
  ```
- Auto-uncheck completed steps and visually mark them as done in the stepper
- User can still manually override (e.g. re-run metadata even if already done)

---

### 2. Backend: Pipeline State Detection

**File: `api.py` (new command: `detect`)**

Add a new `detect` command that inspects an output folder and returns JSON:

```python
elif command == "detect":
    # api.py detect --output /path/to/folder [--html memories_history.html]
    folder = output_folder or DEFAULT_OUTPUT
    state = detect_pipeline_state(folder, html_file)
    print(json.dumps(state), flush=True)
```

**New function `detect_pipeline_state(folder, html_file=None)`:**

```python
def detect_pipeline_state(folder, html_file=None):
    state = {}
    
    # Step 1: Check for downloaded files
    manifest = os.path.join(folder, '..', 'downloaded_files.json')  # or alongside
    media_files = glob(os.path.join(folder, '*.jpg')) + glob(...mp4, etc)
    state['download'] = {
        'done': len(media_files) > 0,
        'fileCount': len(media_files),
        'hasManifest': os.path.exists(manifest)
    }
    
    # Step 2: Check for metadata
    metadata_json = os.path.join(folder, '..', 'metadata.json')
    state['metadata'] = {
        'done': os.path.exists(metadata_json),
        'hasMetadataJson': os.path.exists(metadata_json)
    }
    
    # Step 3: Check for unmerged overlay folders
    overlay_folders = [d for d in os.listdir(folder) 
                       if os.path.isdir(os.path.join(folder, d))
                       and has_overlay_pair(os.path.join(folder, d))]
    state['overlays'] = {
        'done': len(overlay_folders) == 0 and state['download']['done'],
        'pendingFolders': len(overlay_folders)
    }
    
    # Step 4: Dedup is idempotent
    state['dedupe'] = {'done': False}
    
    # Suggest start step
    if not state['download']['done']:
        state['suggestedStartStep'] = 1
    elif not state['metadata']['done']:
        state['suggestedStartStep'] = 2
    elif not state['overlays']['done']:
        state['suggestedStartStep'] = 3
    else:
        state['suggestedStartStep'] = 4
    
    return state
```

---

### 3. IPC: New Handlers in `main.js`

**New IPC handlers:**

```javascript
// Detect pipeline state for an output folder
ipcMain.handle('detect-pipeline-state', async (event, folderPath) => {
  const code = await runApi('detect', ['--output', folderPath]);
  // Parse the JSON output from api.py detect command
  return pipelineState;
});

// Select folder dialog (already exists, but may need to trigger detection)
ipcMain.handle('select-folder', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openDirectory']
  });
  return result.filePaths[0];
});

// New: Select ZIP file for import
ipcMain.handle('select-zip-or-folder', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openFile', 'openDirectory'],
    filters: [
      { name: 'Snapchat Export', extensions: ['zip'] },
      { name: 'All Files', extensions: ['*'] }
    ]
  });
  return result.filePaths[0];
});
```

---

### 4. ZIP Import Support

**File: `snapchat-downloader.py` (or new `import.py`)**

If the user points to a ZIP file instead of a folder, auto-extract it to the output directory first:

- Detect if it's a Snapchat data export ZIP (contains `memories_history.html` inside)
- Extract to a temp dir, then move contents to the output folder
- Set `htmlFile` path automatically if the HTML is found inside the ZIP
- Detect if it's a previously-exported output ZIP (contains media files directly)

**Detection heuristic:**
```
ZIP contains memories_history.html?
  → It's a raw Snapchat export. Extract HTML + set as source.
ZIP contains .jpg/.mp4 files directly?
  → It's a previously-exported output. Extract to output folder.
ZIP contains downloaded_files.json?
  → It's a previous SnapVault run. Extract + detect state.
```

---

### 5. GUI: Smart Toggle Behavior

**File: `gui/app/page.tsx` (state coordinator)**

When pipeline detection returns:
1. **Auto-disable completed steps** — grey out toggle + show ✅
2. **Show "Re-run" option** — small link under each completed step: "Re-run anyway"
3. **Update stepper** — mark completed steps as green/done
4. **Pre-select "Start from Step N"** — set the suggested start step
5. **Show file counts** — "214 files downloaded", "12 overlay folders pending"

---

### 6. `downloaded_files.json` Location Fix

Currently, `downloaded_files.json` is written to the **working directory** (project root), not the output folder. This means if a user moves the output folder, the manifest is lost.

**Proposed fix:** Write `downloaded_files.json` and `metadata.json` **inside the output folder** so everything stays together:

```
snapchat_memories/
├── downloaded_files.json    ← manifest (moved here)
├── metadata.json            ← GPS data (moved here)
├── download_errors.json     ← error log (moved here)
├── 20231012_143022_abc123.jpg
├── 20230928_201510_def456.mp4
├── 20230815_091234_ghi789/   ← unmerged overlay folder
│   ├── ghi789-main.jpg
│   └── ghi789-overlay.png
└── ...
```

This change requires updating all 4 Python scripts to look for JSON files in the `--output` directory rather than cwd. This is the **most impactful backend change** and should be done carefully.

---

## Implementation Order

| Phase | Task | Effort | Risk |
|---|---|---|---|
| **Phase 1** | Add `detect` command to `api.py` + detection logic | Small | Low |
| **Phase 2** | Add `detect-pipeline-state` IPC handler in `main.js` | Small | Low |
| **Phase 3** | Add `select-folder` IPC handler (if not already present) | Trivial | None |
| **Phase 4** | Update Dashboard UI with resume detection display | Medium | Low |
| **Phase 5** | Move JSON manifests into output folder (all 4 scripts) | Medium | **Medium** — regression risk |
| **Phase 6** | ZIP import support (extract + detect) | Medium | Low |
| **Phase 7** | Smart toggle behavior (auto-disable, re-run, counts) | Small | Low |

**Phases 1-4 can ship independently** without changing any backend behavior. Phase 5 is a breaking change for existing users who have `downloaded_files.json` in their project root — should include a migration step.

---

## Open Questions

1. **Should we support importing from other Snapchat download tools?** Some users may have downloaded their memories using other tools (e.g. the old Snapchat data export format). If so, we'd need to handle different folder structures.

2. **Manifest migration**: When moving `downloaded_files.json` into the output folder, should we auto-detect and migrate the old location? (Probably yes — check cwd and project root as fallbacks.)

3. **Multi-folder support**: Should a user be able to point to multiple output folders from different runs and merge them? (Probably out of scope for v1.)
