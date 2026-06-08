# SnapVault KMP — Dev Plan

> Branch: `feature/kmp-modernization`  
> Last updated: 2026-06-04

---

## ✅ Completed (all as of 2026-06-04)

| # | Task |
|---|------|
| 0 | Stitch redesigns (Desktop + Mobile) |
| 1 | Remove native title bar + WindowDraggableArea |
| 2 | Sidebar cleanup (logo block + Start Sync removed) |
| 3 | Dark / Light mode + readability audit |
| 4 | Dashboard simplification (no Setup card, new progress ring UX) |
| 5 | Settings page (workers slider, theme toggle, dependency cards) |
| 6 | App version from Gradle (`AppBuildConfig.VERSION`) |
| 7 | Debug build mode badge + 5-item limit + remove test-run |
| 8 | Real Library page (file scanning + async thumbnail loading) |
| 9 | Scrollable layouts (Settings, Dashboard left col, Library inspector) |
| 10 | Library inspector real data (`fileSizeBytes`, computed GPS/overlay counts) |
| 11 | Contrast & adaptive color audit (`Color.White` → theme tokens, DependencyItem fix) |
| 12 | Vault manifest — write `vault_index.json` after pipeline; read in scanner for `hasGps`/`hasOverlay` |

---

## 9. Scrollable Layouts

**Problem:** Several screens use `fillMaxSize()` layouts with no scroll. Content that overflows the visible area is silently clipped — the user has no indication anything is hidden and no way to reach it.

**Screens affected:**

### SettingsScreen
The outer `Column(Modifier.fillMaxSize().padding(28.dp))` has three stacked cards + footer. On any window shorter than ~700 dp the Advanced card clips or disappears entirely.

Fix:
```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(28.dp),
    ...
)
```
Remove the `Spacer(Modifier.weight(1f))` that pushes the footer — it conflicts with `verticalScroll` (scroll parent can't host `weight` children). Pin the footer at the bottom with a `Spacer(Modifier.height(24.dp))` before it instead.

### DashboardScreen — left column
The left column `Column(Modifier.weight(0.4f), verticalArrangement = Arrangement.spacedBy(16.dp))` places Source card + Pipeline card + `Spacer(weight(1f))` + action buttons. On short windows the cards get clipped before the buttons.

Fix: replace `Spacer(Modifier.weight(1f))` between cards and buttons with a fixed `Spacer(Modifier.height(16.dp))`, then wrap the whole column content in a `Column(Modifier.verticalScroll(...))` nested inside the weight column. The scroll host must not use `fillMaxHeight` — let it size to content.

### LibraryScreen — inspector panel
The inspector `Column(Modifier.fillMaxSize().padding(18.dp))` has storage stats + divider + metadata + divider + vault tools. On short windows the vault tools row is cut.

Fix:
```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(18.dp),
    ...
)
```

**Files:** `SettingsScreen.kt`, `DashboardScreen.kt`, `LibraryScreen.kt`

---

## 10. Library Inspector — Real Data

**Problem:** All inspector numbers are hardcoded (84% storage, 42.1 GB, 214 GPS items, 12 overlay items).

### Step A — add `fileSizeBytes` to LibraryItem
```kotlin
data class LibraryItem(
    ...
    val fileSizeBytes: Long = 0L   // populated by desktopMain scanner
)
```
In `desktopMain/MediaScanner.kt`:
```kotlin
fileSizeBytes = file.length()
```

### Step B — replace inspector content with computed stats
The inspector section of `LibraryScreen` already has access to the full `items` list (not the filtered list). Derive:

```kotlin
val totalBytes  = items.sumOf { it.fileSizeBytes }
val gpsCount    = items.count { it.hasGps }
val overlayCount = items.count { it.hasOverlay }
```

**Storage row:** Remove the progress bar (no quota to compare against). Replace with a flat two-column stat row:
```
📁  N files                    X.X GB total
```
Helper to format bytes:
```kotlin
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    else                    -> "$bytes B"
}
```

**Metadata rows:** Replace hardcoded subtitle strings:
- GPS: `"$gpsCount item${if (gpsCount == 1) "" else "s"} tagged"`
- Overlay: `"$overlayCount asset${if (overlayCount == 1) "" else "s"} combined"`

When `items` is empty (no folder selected) show `"—"` for all stats.

**Files:** `LibraryScreen.kt`, `MediaScanner.kt` (commonMain + desktopMain)

---

## 11. Contrast & Adaptive Color Audit

**Problem:** Many hardcoded `Color.White.copy(alpha = N)` values that look fine in dark mode become invisible in light mode (white-on-white). Separately, several dark-mode-specific backgrounds create poor text contrast.

### A — DependencyItem background (root cause of ExifTool note issue)
`DependencyItem` uses `background(MaterialTheme.colorScheme.background.copy(alpha = 0.4f))`. In dark mode, `background = #081425` at 40% alpha blended over the card surface produces an extremely dark box where the `onSurfaceVariant.copy(alpha = 0.5f)` description text (`#CBC3D7`) renders as an effective ~`#6E6B7B` — below readable contrast.

Fixes:
```kotlin
// background: was background.copy(alpha = 0.4f)
.background(MaterialTheme.colorScheme.surfaceContainerLow)

// description text: was alpha = 0.5f
color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
```

### B — Card borders (Color.White → outlineVariant)
All `BorderStroke(1.dp, Color.White.copy(alpha = 0.05f / 0.06f / 0.08f / 0.1f))` are invisible in light mode. Sweep every screen file:

```kotlin
// was: Color.White.copy(alpha = 0.05f)
MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
```

Files with occurrences: `DashboardScreen.kt` (×6), `SettingsScreen.kt` (×2), `LibraryScreen.kt` (×5), `App.kt` (×2).

### C — Structural backgrounds (Color.White → surfaceContainer tokens)
Elements that use `Color.White.copy(alpha = 0.04f)` as a background are invisible in light mode:
- `MetricChip` background → `MaterialTheme.colorScheme.surfaceContainerLow`
- Stop button disabled background → `MaterialTheme.colorScheme.surfaceContainerLow`
- StepItem inactive circle → `MaterialTheme.colorScheme.surfaceContainerLow`

### D — Slider / progress track colors
- `inactiveTrackColor = Color.White.copy(alpha = 0.08f)` (Settings workers slider) → `MaterialTheme.colorScheme.surfaceContainerHighest`
- `trackColor = Color.White.copy(alpha = 0.06f)` (circular progress ring) → `MaterialTheme.colorScheme.surfaceContainerHigh`
- `trackColor = Color.White.copy(alpha = 0.05f)` (library storage progress bar) → `MaterialTheme.colorScheme.surfaceContainerHighest`

### E — StepperDivider and step circle accents
- `StepperDivider` unfilled: `Color.White.copy(alpha = 0.06f)` → `MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)`
- Step circle inactive border: `Color.White.copy(alpha = 0.1f)` → `MaterialTheme.colorScheme.outlineVariant`

**Files:** `DashboardScreen.kt`, `SettingsScreen.kt`, `LibraryScreen.kt`, `App.kt`

---

## 12. Vault Manifest (GPS + Overlay cross-reference)

**Problem:** `LibraryItem.hasGps` and `hasOverlay` are always `false` in the scanner because the download pipeline doesn't persist what metadata was written to which file.

### A — Write `vault_index.json` after pipeline completes
In `App.kt` pipeline, maintain a `downloadedMeta: MutableMap<String, FileMeta>` where:
```kotlin
@Serializable
data class FileMeta(val hasGps: Boolean, val hasOverlay: Boolean)
```

After each download or GPS write, update the map:
```kotlin
downloadedMeta[filename] = FileMeta(hasGps = exifOk, hasOverlay = false)
```

At end of pipeline, serialise to `$outDir/vault_index.json`:
```kotlin
fileSystem.write("$outDir/vault_index.json".toPath()) {
    writeUtf8(Json.encodeToString(downloadedMeta))
}
```

### B — Read manifest in `scanMediaFiles`
In `desktopMain/MediaScanner.kt`, before mapping files:
```kotlin
val indexFile = File(folderPath, "vault_index.json")
val index: Map<String, FileMeta> = if (indexFile.exists())
    runCatching { Json.decodeFromString(indexFile.readText()) }.getOrDefault(emptyMap())
else emptyMap()
```

Then populate per file:
```kotlin
val meta = index[file.name]
LibraryItem(
    ...
    hasGps = meta?.hasGps ?: false,
    hasOverlay = meta?.hasOverlay ?: false
)
```

**Files:** `App.kt`, `desktopMain/MediaScanner.kt`, new `commonMain/model/FileMeta.kt`

---

## Execution Order

| # | Task | Notes |
|---|------|-------|
| 9 | Scrollable layouts | Quick wins, no dependencies |
| 10 | Library inspector real data | Needs `fileSizeBytes` in LibraryItem |
| 11 | Contrast & adaptive color audit | Independent, broad sweep |
| 12 | Vault manifest (GPS/overlay) | Needs App.kt pipeline change |
