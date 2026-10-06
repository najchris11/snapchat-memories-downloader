# Verbiage audit — 2026-09-28

Scope: `strings.xml`, every hardcoded user-visible literal (including log lines), and the
desktop picker titles. Docs excluded. Nothing has been changed yet.

## Contents

1. [Wording that states something false](#1-wording-that-states-something-false)
2. [Ambiguous or alarming](#2-ambiguous-or-alarming)
3. [One concept, several names](#3-one-concept-several-names)
4. [Jargon users shouldn't see](#4-jargon-users-shouldnt-see)
5. [Hardcoded strings](#5-hardcoded-strings-claudemd-strings-rule)
6. [Needs a human check](#6-needs-a-human-check)
7. [Suggested order](#7-suggested-order)

---

## 1. Wording that states something false

These mislead rather than just read badly — fix first.

### 1.1 "N steps failed" counts files, not steps

- **Where:** `warn_run_failures_title`
- **Says:** "3 steps failed"
- **Actually:** `failureCount` sums per-file failures — extraction errors, metadata tags,
  combines, deletions (`DashboardViewModel.kt:941`, `:1228`, `:1348`, `:1390`, `:1588`, `:1630`).
  One bad metadata pass can show "47 steps failed" in a four-step run.
- **Suggest:** "47 files failed" (plural resource) — the body already says "Some files were not
  processed".

### 1.2 "Write Date Metadata" also writes GPS

- **Where:** `opt_write_date_metadata` (ZIP mode label)
- **Actually:** with **Precise time + GPS matching** on — the default — this switch writes
  locations too. The only switch labelled date-only is the one that can embed where you were.
- **Suggest:** "Write date and location metadata", with the precise-matching toggle beneath it
  controlling whether location is included.

### 1.3 Android banner promises what Android can't do

- **Where:** `banner_android_preview_body`
- **Says:** "ZIP extraction and date/GPS tagging for images work."
- **Actually:** `androidMain/ZipReader.kt` returns `emptyList()` / `emptyMap()` / `null`, so a ZIP
  import finds no memories. `androidMain/MediaScanner.kt` returns `emptyList()`, so the Library
  is always empty.
- **Suggest:** state plainly that Android is an early preview and imports don't work yet — or
  hide the import path until the stubs are filled.

### 1.4 Onboarding promises a location the Library never shows

- **Where:** `onb_run_review`
- **Says:** "Click into a photo to see the date and location that were recovered"
- **Actually:** the detail pane shows GPS as "Tagged" / "No data", never a place or coordinates.
  "Click" is also wrong on phones.
- **Suggest:** "Open a photo to see its recovered date and whether a location was saved."

### 1.5 Combine skip reason is stale

- **Where:** log, `DashboardViewModel.kt:1385`
- **Says:** "N skipped (unsupported format)"
- **Actually:** skips now also cover an already-existing output, animated GIFs, and video on
  mobile.
- **Suggest:** "N skipped (see warnings above)".

### 1.6 Log points users at stderr

- **Where:** log, `DashboardViewModel.kt:1396`
- **Says:** "(see stderr for per-file reasons)"
- **Actually:** end users can't see stderr; the reasons only go to `System.err`
  (`DesktopMediaProcessor.kt:366`, `:369`).
- **Suggest:** route those two lines to the app log, or drop the pointer.

### 1.7 "Harmless" WebP skip is conditional

- **Where:** log, `DashboardViewModel.kt:1337`
- **Says:** WebP-as-PNG overlays are "harmless, they're consumed by the combine phase"
- **Actually:** only true when Combine is on.
- **Suggest:** drop the clause when `runCombine` is off.

### 1.8 The default is called "experimental"

- **Where:** `zip_precise_matching_log` vs logs `DashboardViewModel.kt:965`, `:970`
- **Says:** "(default)" in one line, "Experimental metadata matching" in the next.
- **Suggest:** drop "Experimental" from user-facing text; call it precise matching everywhere.

### 1.9 "Each phase is a switch" — not in ZIP mode

- **Where:** `onb_pipeline_body`
- **Says:** "A run is four phases. Each one is a switch on the Dashboard"
- **Actually:** in ZIP mode (the recommended one) there's no Download switch — it only exists in
  Legacy mode.
- **Suggest:** "Most of them are switches on the Dashboard…"

---

## 2. Ambiguous or alarming

- **Unsaved-favorites dialog** — `close_unsaved_body`: "It exists nowhere else — quitting now
  will lose it." Reads as though the *photo* is lost; only the favorite mark is.
  Suggest: "1 favorite hasn't been saved yet. Quitting now will unmark it."

- **Library inspector labels** (`LibraryScreen.kt:905–927`):

  | Label | Problem | Suggest |
  |---|---|---|
  | "Metadata Extraction" | The app writes metadata, it doesn't extract it | "Metadata" |
  | "GPS Data Verified" | Nothing is verified; the file just carries GPS | "Location saved" |
  | "Overlay Detected" + "N assets combined" | Title and subtitle contradict | "Overlays combined" |
  | "No data" (GPS row) | Unclear what's missing | "No location" |

- **Dedupe preview has two names** — the UI calls it "Preview duplicate removal"; the log says
  "dry run" and "Disable dry run to apply" (`DashboardViewModel.kt:1628`). No control is called
  "dry run".
  Suggest: "Preview complete — N duplicates would be deleted. Turn off *Preview duplicate
  removal* to delete them."

- **"Open the original"** — `lib_preview_unavailable`. After a combine the original has been
  deleted. Suggest "open the file".

- **"The index"** — `set_reset_result_failed` says "the index could not be changed"; the UI
  never introduces an index, and the logs call it the "vault index". Suggest "the Library's saved badges". 

- **UUIDs in errors** — `DashboardViewModel.kt:1448`: "Overlay combine 3fa9c2e1: error…" names
  the item by a truncated UUID the user can't search for. Use the filename.

- **Layout setting** — `set_layout_description` explains Auto and Compact, not Expanded.
  Suggest: "Auto follows the window width. Compact and Expanded force the phone or desktop
  layout."

- **Output-folder headroom** — `onb_folders_space`: "the media is written out alongside the
  archives" (the output folder isn't necessarily next to the ZIPs), and "a few gigabytes"
  undersells large exports. Suggest: "Leave free space in the output folder of roughly the size
  of your ZIPs…"

---

## 3. One concept, several names

Pick one term per row and use it everywhere users look.

| Concept | Current variants | Notes |
|---|---|---|
| The main action | Start **Download**, **Sync** complete, a **sync** is running, **run**, **import**, **pipeline** | In ZIP mode nothing downloads, yet `btn_start_sync`, `dash_step_syncing` ("Downloading"), `status_idle` ("Ready to download") and `lib_empty_no_media` ("Start a download") all say download. **Needs your decision:** "import" or "run". |
we can do run
| Overlay step | **Combine** (Dashboard), **Merge video** overlays (`onb_pipeline_combine_title`), "merge" (`opt_combine_cleanup_helper`) | It does photos as well as videos. |
| Output location | Output **Folder** (Dashboard), Output **Path** + "Edit" (Settings) | |
| Logs | "View **Logs**" (`log_view_logs`), "View **log**" (`btn_view_log`), "Copy logs" | |
| Export site | `mydata.snapchat.com` (`HistoryParser.kt:154`, `DashboardViewModel.kt:1042`) vs `accounts.snapchat.com/v2/download-my-data` (onboarding) | |
yeah the accounts.snapchat.com link is the correct one, idk what the other one is
| Base file of a pair | "could not delete **main**" (desktop, `OverlayCombiner.kt:269`) vs "could not delete **original**" (Android, iOS) | "Main" is internal jargon. |
| Existing-output warning | "output already exists, **pair** left alone" (shared) vs "output already exists, left alone" (iOS `IosMediaProcessor.kt:204`) | |
| Capitalisation | Title Case ("Clean Duplicate Files", "Write Date Metadata", "Refresh Check") next to sentence case ("Combine photo and video overlays", "Preview duplicate removal") in the same list | Pick sentence case — it's what the newer strings use. |

---

## 4. Jargon users shouldn't see

- **"Pipeline"** — "Pipeline Options" (`dash_pipeline_title`), "where the pipeline writes"
  (`lib_empty_no_folder`), "Pipeline Complete" (progress text).
- **Settings subtitle** — "Manage system dependencies and utility preferences."
- **ExifTool description** — "GPS metadata injection". It writes dates too, and "injection" is
  developer-speak. Suggest "Writes dates and locations into files".
- **"Refresh Check"** — suggest "Check again".
- **"Legacy (HTML/JSON)"** — gives no hint of when to pick it. Its placeholder only shows
  `memories_history.json`, though the picker also accepts `.html`.
  Maybe it makes sense to get rid of the options there and just outright let the user provide the html file if they have one? but otherwise we just let the user point to a folder of zips or a specific zip? is that something that can be done? if so, we absolutely need regression tests and a feature branch to pick that up
- **The `mydata~*.zip` glob** — in `zip_folder_placeholder` and the hardcoded picker text.
- **"this row's link"** — `DownloadEngine.kt:383`.
- **Logs** — "(s)" plurals throughout ("file(s)", "archive(s)", "zip(s)"); "zip" and "ZIP"
  mixed.

---

## 5. Hardcoded strings (CLAUDE.md Strings rule)

These should move to `strings.xml`:

- `DashboardScreen.kt:257` — "Select mydata~*.zip files…"
- `DashboardScreen.kt:259` — "N ZIP files selected" (also needs a plural resource)
- `DashboardScreen.kt` (same block) — "+ N more"
- `DesktopPickers.kt:10`, `:24`, `:28`, `:52` — the four picker titles
- `DashboardViewModel.kt` — every `progressText` ("Extracting files…", "Combining: 3 / 40",
  "Deduplicating: Found N duplicates", "Pipeline Complete", "Completed with warnings", …)

---

## 6. Needs a human check

- `onb_request_body` gives the Snapchat menu path "Export your Memories → Request Only Memories
  → All Time". Snapchat changes this UI often; confirm it against the live page.
  that's pretty accurate but there's a video to clarify if needed

---

## 7. Suggested order

1. Section 1 — especially 1.1–1.3.
2. Decide the name for the main action (section 3, first row), then sweep for it.
3. Sections 2 and 3.
4. Sections 4 and 5 alongside, since most fixes there touch the same lines.
