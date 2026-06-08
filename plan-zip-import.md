# SnapVault — ZIP Import Feature Plan

> Branch: `feature/kmp-modernization`  
> Created: 2026-06-08  
> Context: Snapchat changed their data export. Instead of a single `memories_history.html` with CDN download links, exports are now a set of zip files containing pre-packaged media.

---

## Observed Export Structure

The new export is a set of zip files (e.g. `mydata~{timestamp}.zip`, `mydata~{timestamp}-2.zip`, … `-21.zip`).

### Main (unnumbered) zip — the metadata hub
```
html/
  memories_history.html   ← legacy table HTML (links are N/A / expired, but GPS + full datetime are here)
  faq.html
json/
  memories_history.json   ← ⭐ the real metadata: full UTC datetime + GPS + media type for every memory
index.html
memories/
  memories.html           ← local HTML index (references files below by relative path)
  YYYY-MM-DD_UUID-main.jpg / .mp4
  YYYY-MM-DD_UUID-overlay.png
```

### Numbered zips (-2, -3, … -21) — media-only
```
memories/
  memories.html           ← same local index format
  YYYY-MM-DD_UUID-main.jpg / .mp4
  YYYY-MM-DD_UUID-overlay.png
```

### `memories_history.json` schema
```json
{
  "Saved Media": [
    {
      "Date": "2026-06-05 16:23:27 UTC",
      "Media Type": "Video",
      "Location": "Latitude, Longitude: 40.012688, -83.066986",
      "Download Link": "",
      "Media Download Url": ""
    },
    ...
  ]
}
```
- **Sorted newest-first** (most recent memory at index 0)
- Total entries: ~10,233
- GPS is real for entries where location was enabled (0.0,0.0 for those with location off)

### `memories/memories.html` schema (per zip)
```html
<div class="image-container">
  <video src=".//YYYY-MM-DD_UUID-main.mp4" ...></video>
  <!-- optional overlay: -->
  <img class="overlay-image" src=".//YYYY-MM-DD_UUID-overlay.png" ...>
  <div>
    <div class="text-line">YYYY-MM-DD</div>
  </div>
</div>
```
- Each `image-container` = one memory (main file, optionally paired with overlay)
- **Sorted oldest-first within each zip**; zips are also oldest-first numerically
- Total across all zips: ~10,104

### Zip chronological ordering
| Zip | Date range |
|-----|-----------|
| main (no suffix) | 2015-01-01 → ~2017-11 |
| -2 | 2017-11-17 → 2018-09-18 |
| -3 | 2018-09-19 → 2019-02-22 |
| … | … |
| -21 | 2025-05-25 → 2026-06-05 |

---

## The GPS Correlation Problem

The JSON has **full datetime + GPS** but **no filename or UUID**.  
The media files have **UUID** but **only YYYY-MM-DD** (no time, no GPS).  
There is no direct join key.

### Solution: Position-based correlation with date validation

Because both datasets are in strict chronological order:
- **JSON reversed** → oldest-first
- **All `memories.html` entries collected in zip order** (main → -2 → -3 → … → -21) → oldest-first

Matching: position N in reversed JSON ↔ position N in the ordered memories list.

**Validation rule:** the date portion of the JSON's `"Date"` field (`YYYY-MM-DD`) must equal the `text-line` date for that position. If they match, the GPS and full datetime are confidently assigned to that UUID. If they don't match, the position is skipped and the GPS is left unset for that file (fallback: date-only from filename).

**Why this works:** each zip's memories.html lists entries in the same chronological order Snap uses for the JSON. The ~129-entry count difference (10,233 JSON vs 10,104 memories) comes from story saves or other non-memories entries in the JSON that have no corresponding media file; these create date mismatches that the validation rule safely discards.

**Bonus from JSON match:** when a match is confirmed, we get the **full UTC datetime** (HH:MM:SS) instead of just YYYY-MM-DD — much better for EXIF `DateTimeOriginal`.

### Implementation: `MetadataCorrelator`
```kotlin
// commonMain
data class CorrelatedMeta(
    val uuid: String,
    val fullDateTime: String,   // "2026-06-05 16:23:27 UTC"
    val latitude: Double?,      // null if 0.0,0.0 or no match
    val longitude: Double?
)

object MetadataCorrelator {
    // jsonEntries: parsed from memories_history.json (newest-first)
    // memoriesEntries: collected from all memories.html in zip order (oldest-first within each)
    // Returns map of UUID → CorrelatedMeta for confirmed matches only
    fun correlate(
        jsonEntries: List<JsonMemoryEntry>,   // Date, MediaType, lat, lon
        memoriesEntries: List<HtmlMemoryEntry> // fileName, date, isVideo
    ): Map<String, CorrelatedMeta>
}
```

The correlator reverses the JSON list, then walks both lists simultaneously, matching on date and media type (Video↔.mp4, Image↔.jpg). When a match is found, emits a `CorrelatedMeta` keyed by the UUID extracted from the filename.

---

## Overlay Combining

The legacy `combine_overlays.py` worked on **subfolders** (old format created one subfolder per memory). The new format has all files **flat in one directory** using the `-main` / `-overlay` suffix convention.

Same logic, different filesystem view:

| Aspect | Legacy | New (zip) |
|--------|--------|-----------|
| Structure | `outDir/{UUID}/main.jpg` + `outDir/{UUID}/overlay.png` | `outDir/DATE_UUID-main.jpg` + `outDir/DATE_UUID-overlay.png` |
| Pair detection | Walk subdirs, find `*-main.*` + `*-overlay.*` within each | Walk outDir, group files by UUID prefix |
| Output | `outDir/{UUID}.jpg` | `outDir/DATE_UUID.jpg` (drop `-main` suffix) |
| Cleanup | Delete subfolder | Delete `-main.*` and `-overlay.png` files |

### Tools needed (same as legacy)
- **Pillow** — image+overlay compositing (RGBA alpha_composite → convert to RGB for JPEG output)
- **FFmpeg** — video+overlay burn-in (`[1:v]format=rgba[ov];[ov][0:v]scale2ref…[base][ovr]overlay`)
- **exiftool** — copy EXIF from original main to combined output (`-TagsFromFile -all:all`)

These are already available in the KMP app via `MediaProcessor`.

### `OverlayCombiner` (new desktopMain class)
```kotlin
class OverlayCombiner(private val mediaProcessor: MediaProcessor) {

    data class CombineResult(
        val uuid: String,
        val outputPath: String,
        val status: String  // "combined" | "skipped" | "error: ..."
    )

    // Scans outputDir for *-main.* files, pairs with matching *-overlay.png
    // Calls combineImage or combineVideo per pair
    // Deletes originals on success if deleteOriginals=true
    suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onProgress: (CombineResult) -> Unit
    )

    private fun findPairs(outputDir: String): List<OverlayPair>

    data class OverlayPair(
        val mainPath: String,
        val overlayPath: String,
        val outputPath: String,
        val isVideo: Boolean
    )
}
```

Pair detection logic:
```kotlin
// UUID = everything between the first _ and -main or -overlay
val mainFiles = dir.listFiles { f -> "-main." in f.name }
val overlayMap = dir.listFiles { f -> "-overlay." in f.name }
    .associateBy { extractUuid(it.name) }

fun extractUuid(name: String): String =
    name.substringAfter("_").substringBefore("-main").substringBefore("-overlay")
```

---

## Implementation Plan

### Step 0 — Source mode toggle (UI + state)

Add `ImportMode` enum and toggle on DashboardScreen.

```kotlin
enum class ImportMode { Legacy, Zip }
var importMode by remember { mutableStateOf(ImportMode.Zip) }
var zipFolder by remember { mutableStateOf<String?>(null) }
```

DashboardScreen gets: `importMode`, `zipFolder`, `onSelectZipFolder`, `onImportModeChange`.

When ZIP mode is active, replace the HTML file picker with a ZIP folder picker. Keep the output folder picker.

**Files:** `App.kt`, `DashboardScreen.kt`

---

### Step 1 — `ZipImportParser` (commonMain)

**Path:** `composeApp/src/commonMain/kotlin/com/najdev/snapvault/parser/ZipImportParser.kt`

Parses a `memories/memories.html` string and returns structured entries.

```kotlin
data class HtmlMemoryEntry(
    val fileName: String,   // "2024-01-15_UUID-main.jpg"
    val uuid: String,
    val date: String,       // "2024-01-15"
    val isVideo: Boolean,
    val hasOverlay: Boolean,
    val overlayFileName: String?
)

object ZipImportParser {
    // Parses the memories.html string from one zip
    fun parseMemoriesHtml(html: String): List<HtmlMemoryEntry>

    // Regex for extracting UUID from filename
    private val nameRegex = Regex(
        """^(\d{4}-\d{2}-\d{2})_([A-Za-z0-9\-]+)-(main|overlay)\.(\w+)$"""
    )
}
```

Each `image-container` has one `<img src>` or `<video src>` (the main) and optionally a second `<img class="overlay-image">`. Both `src` attributes give the filenames; strip the `.//` prefix.

**Files:** new `ZipImportParser.kt`

---

### Step 2 — `ZipJsonParser` (commonMain)

**Path:** `composeApp/src/commonMain/kotlin/com/najdev/snapvault/parser/ZipJsonParser.kt`

Parses `memories_history.json` from the main zip.

```kotlin
data class JsonMemoryEntry(
    val date: String,        // "YYYY-MM-DD HH:MM:SS UTC"
    val dateOnly: String,    // "YYYY-MM-DD"
    val mediaType: String,   // "Video" or "Image"
    val latitude: Double?,   // null if 0.0,0.0
    val longitude: Double?
)

object ZipJsonParser {
    fun parse(jsonContent: String): List<JsonMemoryEntry>  // preserves newest-first order
}
```

GPS: parse `"Latitude, Longitude: lat, lon"` — same regex as existing `HistoryParser`. If both are 0.0, emit null/null.

**Files:** new `ZipJsonParser.kt`

---

### Step 3 — `MetadataCorrelator` (commonMain)

**Path:** `composeApp/src/commonMain/kotlin/com/najdev/snapvault/parser/MetadataCorrelator.kt`

```kotlin
data class CorrelatedMeta(
    val uuid: String,
    val fullDateTime: String,  // "2026-06-05 16:23:27 UTC"
    val latitude: Double?,
    val longitude: Double?
)

object MetadataCorrelator {
    fun correlate(
        jsonEntries: List<JsonMemoryEntry>,       // newest-first
        htmlEntries: List<HtmlMemoryEntry>        // oldest-first (already aggregated in zip order)
    ): Map<String, CorrelatedMeta>
}
```

Algorithm:
1. Reverse `jsonEntries` → oldest-first
2. Walk both lists with two pointers
3. For each HTML entry (UUID known): find first JSON entry at the same position
4. Validate: `jsonEntry.dateOnly == htmlEntry.date` AND `(jsonEntry.mediaType == "Video") == htmlEntry.isVideo`
5. If valid: emit `CorrelatedMeta(uuid, fullDateTime, lat, lon)`
6. If invalid: advance the JSON pointer (skip that JSON entry — it's a story or other type) and retry
7. Skip at most 5 JSON entries per HTML entry before giving up on that HTML entry (prevents runaway drift)

**Files:** new `MetadataCorrelator.kt`

---

### Step 4 — `listZipEntries` + `readZipEntry` (expect/actual)

The parser and correlator live in commonMain but need to read zip contents on JVM.

**commonMain expect** (`ZipReader.kt`):
```kotlin
expect fun listZipEntries(zipFilePath: String): List<String>
expect fun readZipEntry(zipFilePath: String, entryName: String): String?
```

**desktopMain actual:**
```kotlin
actual fun listZipEntries(zipFilePath: String): List<String> =
    java.util.zip.ZipFile(zipFilePath).use { zf ->
        zf.entries().toList().map { it.name }
    }

actual fun readZipEntry(zipFilePath: String, entryName: String): String? =
    java.util.zip.ZipFile(zipFilePath).use { zf ->
        zf.getEntry(entryName)?.let { entry ->
            zf.getInputStream(entry).bufferedReader().readText()
        }
    }
```

**androidMain / iosMain actual:** return `emptyList()` / `null` (stub; ZIP import is desktop-only).

**Files:** new `ZipReader.kt` in commonMain + desktopMain + androidMain + iosMain

---

### Step 5 — `ZipExtractEngine` (desktopMain)

**Path:** `composeApp/src/desktopMain/kotlin/com/najdev/snapvault/downloader/ZipExtractEngine.kt`

Extracts the actual media files from each zip to the output directory.

```kotlin
class ZipExtractEngine(private val fileSystem: FileSystem) {

    data class ExtractResult(
        val uuid: String,
        val outputPath: String,
        val skipped: Boolean,
        val error: String?
    )

    // items: from ZipImportParser, keyed by zip file
    // Extracts main + overlay for each item; strips "memories/" prefix; skips memories.html
    // Skips if outputDir/fileName already exists
    suspend fun extractAll(
        itemsByZip: Map<String, List<HtmlMemoryEntry>>,
        outputDir: String,
        workerCount: Int,
        onProgress: (ExtractResult) -> Unit
    )
}
```

Uses `java.util.zip.ZipFile` streaming per-entry — never decompresses the whole zip to disk at once.

**Files:** new `ZipExtractEngine.kt`

---

### Step 6 — `writeDateMetadata` in `MediaProcessor`

The existing `writeGpsMetadata` handles GPS. We need a companion that writes the date fields only (for memories with no GPS, or as a prerequisite before GPS).

Check if `MediaProcessor` already has this — if not, add:

```kotlin
// commonMain expect
suspend fun writeDateMetadata(filePath: String, dateTimeUtc: String): Boolean

// desktopMain actual — calls exiftool
// dateTimeUtc: "YYYY-MM-DD HH:MM:SS UTC" or "YYYY-MM-DD" (time defaults to 00:00:00)
// Writes: DateTimeOriginal, CreateDate, ModifyDate (images) or CreateDate, MediaCreateDate, TrackCreateDate, ModifyDate (video)
// Also sets file mtime to match
```

**Files:** `commonMain/metadata/MediaProcessor.kt`, `desktopMain/metadata/MediaProcessor.kt`

---

### Step 7 — `OverlayCombiner` (desktopMain)

**Path:** `composeApp/src/desktopMain/kotlin/com/najdev/snapvault/downloader/OverlayCombiner.kt`

Combines flat `*-main.*` + `*-overlay.png` pairs in the output directory.

- **Images** → Pillow-equivalent in JVM: use `java.awt.image.BufferedImage` with `AlphaComposite.SRC_OVER`
- **Videos** → call `ffmpeg` subprocess via `mediaProcessor` (or directly via `ProcessBuilder`)
- After combine → copy EXIF from original main to combined output via exiftool
- Optionally delete originals (`-main.*` + `-overlay.png`)
- Output filename: drop the `-main` suffix (`DATE_UUID.jpg`)

```kotlin
class OverlayCombiner(private val mediaProcessor: MediaProcessor) {

    data class OverlayPair(
        val mainPath: String,
        val overlayPath: String,
        val outputPath: String,
        val isVideo: Boolean
    )

    data class CombineResult(
        val uuid: String,
        val outputPath: String,
        val status: String   // "combined" | "skipped_no_tool" | "error: ..."
    )

    fun findPairs(outputDir: String): List<OverlayPair>

    suspend fun combineAll(
        outputDir: String,
        deleteOriginals: Boolean,
        workerCount: Int,
        onProgress: (CombineResult) -> Unit
    )

    private fun combineImages(mainPath: String, overlayPath: String, outputPath: String): Boolean

    private suspend fun combineVideo(mainPath: String, overlayPath: String, outputPath: String): Boolean
}
```

`combineImages` using Java2D:
```kotlin
val main = ImageIO.read(File(mainPath))
val overlay = ImageIO.read(File(overlayPath)).let {
    // scale to main dimensions if needed
    val scaled = BufferedImage(main.width, main.height, BufferedImage.TYPE_INT_ARGB)
    scaled.createGraphics().apply {
        drawImage(it, 0, 0, main.width, main.height, null)
        dispose()
    }
    scaled
}
val combined = BufferedImage(main.width, main.height, BufferedImage.TYPE_INT_RGB)
combined.createGraphics().apply {
    composite = AlphaComposite.SrcOver
    drawImage(main, 0, 0, null)
    drawImage(overlay, 0, 0, null)
    dispose()
}
ImageIO.write(combined, "JPEG", File(outputPath))
```

`combineVideo` calls ffmpeg via ProcessBuilder with the same filter_complex as the legacy Python script.

**Files:** new `OverlayCombiner.kt`

---

### Step 8 — Pipeline integration in `App.kt`

New ZIP pipeline branch inside `onStartSync`:

```
1. Scan zipFolder for *.zip files — sort: unnumbered first, then -2, -3, … -N ascending
2. Read memories_history.json from the main (unnumbered) zip → ZipJsonParser.parse()
3. For each zip (in order):
     a. readZipEntry(zip, "memories/memories.html") → ZipImportParser.parseMemoriesHtml()
     b. Accumulate all HtmlMemoryEntry items (oldest-first across zips)
4. MetadataCorrelator.correlate(jsonEntries, allHtmlEntries) → uuid→CorrelatedMeta map
5. ZipExtractEngine.extractAll(itemsByZip, outputDir) → progress updates → logs
6. For each extracted main file:
     a. Look up CorrelatedMeta by UUID
     b. If found: writeDateMetadata(fullDateTime) → if GPS non-null: writeGpsMetadata()
     c. Else: writeDateMetadata(dateFromFilename + "00:00:00")
     d. Update downloadedMeta[fileName] = FileMeta(hasGps = gpsWritten, hasOverlay = item.hasOverlay)
7. If overlaysCombine toggle: OverlayCombiner.combineAll(outputDir, deleteOriginals=true)
8. If dedupe toggle: Deduplicator.deduplicateAll(outputDir)
9. Write vault_index.json
```

Progress steps shown to user:
```
Step 1 ▸ Reading metadata…
Step 2 ▸ Extracting files: 142 / 10,104…
Step 3 ▸ Writing metadata…
Step 4 ▸ Combining overlays: 38 / 284…
Step 5 ▸ Complete
```

**Files:** `App.kt`

---

### Step 9 — DashboardScreen ZIP mode UI

**Mode selector** (above the source card):
```
[ Legacy HTML/JSON ]  [ ZIP Import ▾ ]
```

**ZIP mode source card:**
```
┌──────────────────────────────────────────────┐
│  ZIP Export Folder                           │
│  Select the folder containing your           │
│  mydata~*.zip files                          │
│  [ Select Folder ]                           │
│  /path/to/zips  •  21 zip files found        │
└──────────────────────────────────────────────┘
```

**Pipeline toggles in ZIP mode:**
- ~~Download~~ (hidden — extraction replaces download)
- Write metadata ✓ (date + GPS from JSON correlation)
- Combine overlays ✓ (Pillow for images, FFmpeg for video)
- Deduplicate ✓

**Info chip when GPS correlation is available:**
```
ℹ️  GPS data read from memories_history.json — matched by date
```

**Files:** `DashboardScreen.kt`

---

## Execution Order

| # | Task | Depends on |
|---|------|-----------|
| 0 | Mode toggle (UI stub) | — |
| 1 | `ZipImportParser` | — |
| 2 | `ZipJsonParser` | — |
| 3 | `MetadataCorrelator` | 1, 2 |
| 4 | `listZipEntries` / `readZipEntry` expect/actual | — |
| 5 | `ZipExtractEngine` | 1, 4 |
| 6 | `writeDateMetadata` in MediaProcessor | — |
| 7 | `OverlayCombiner` | — |
| 8 | Pipeline integration in `App.kt` | 1–7 |
| 9 | DashboardScreen ZIP mode UI | 0, 8 |

Steps 1, 2, 4, 6, 7 are fully independent and can be done in parallel.

---

## Open Questions / Edge Cases

1. **Unnumbered zip identification:** Sort by: no `-N` suffix = first; then by numeric suffix ascending. Use regex `Regex("""~\d+(-(\d+))?\.zip$""")` to extract the suffix number; `null` → 0.

2. **Standalone overlay-only entries:** Some entries in memories.html have an overlay PNG but no main file (the overlay IS the content). These have `hasOverlay = false` (no pair) — extract as-is, treat the `-overlay.png` as the primary file.

3. **Count drift in correlator:** If the JSON and HTML lists diverge more than ~5 entries consecutively, reset and continue — GPS simply isn't assigned for that file.

4. **File mtime:** After writing date EXIF, also call `File.setLastModified(epochMs)` to make the file's OS timestamp match.

5. **iOS / Android stubs:** `listZipEntries` and `readZipEntry` return `emptyList()` / `null`. ZIP import is only surfaced in the desktop UI for now.

6. **Overlay combine with Java2D vs Pillow:** Java2D handles JPEG/PNG compositing natively (no Python dependency). The KMP app doesn't have Pillow — use `java.awt.image` + `ImageIO` instead. FFmpeg subprocess remains the same.
