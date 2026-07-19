# Mobile Implementation Plan & Review

Working document for taking SnapVault from "compiles on Android/iOS" (PR #18) to
"shippable on Android/iOS". Combines a code review of the current mobile code, an
honest UI/UX review of the responsive layout, and ordered implementation steps.

Status legend: ✅ works · 🟡 partial / has issues · ❌ stub or missing

---

## 1. Current state audit

| Subsystem | Desktop | Android | iOS |
|---|---|---|---|
| App shell / navigation | ✅ sidebar | ✅ bottom nav (`PhoneRoot`) | ✅ bottom nav |
| Theme + layout persistence | ✅ | ✅ (PR #18) | ✅ (PR #18) |
| File/folder pickers | ✅ | 🟡 wired in `MainActivity`, but see F1–F3 | ❌ all callbacks return null |
| ZIP reading (`listZipEntries` / `readZipEntryText` / `listZipEntryTimestamps`) | ✅ | ❌ stubs | ❌ stubs |
| ZIP pipeline runner (extract / combine) | ✅ | ❌ `NoOpZipPipelineRunner` | ❌ `NoOpZipPipelineRunner` |
| GPS metadata write | ✅ exiftool | 🟡 `ExifInterface` (images only, untested) | ❌ stub |
| Date metadata write | ✅ exiftool | ❌ `writeDateMetadata` returns false | ❌ stub |
| Video + overlay combine | ✅ ffmpeg | ❌ stub | ❌ stub |
| Library scan + thumbnails | ✅ | ❌ stubs → Library always empty | ❌ stubs |
| Video playback | ✅ | 🟡 `VideoView` (bug F6) | 🟡 AVPlayer (untested) |
| App icon | ✅ | ❌ default green Android head | ❌ default blank |

Key structural facts:

- The precise time + GPS matcher (`ExperimentalZipMetadataMatcher`, `ZipImportParser`)
  is **already common code**. It only needs the three `ZipReader` expect functions to
  have real actuals on a platform to light up. On Android those can be the desktop
  implementations verbatim (`java.util.zip` exists on Android) — this is the single
  highest-leverage move in Phase 2.
- `jvmSharedMain` (created in PR #18) is the landing spot: anything in
  `desktopMain` that only uses `java.util.zip` / `java.io` can move there and serve
  desktop + Android from one file. Candidates: `ZipReader.kt`,
  `downloader/ZipExtractEngine.kt`, most of `DesktopZipPipelineRunner.kt`.
  Non-candidates: `OverlayCombiner.kt` (spawns ffmpeg/exiftool processes),
  `BinaryExtractor.kt`, `ProcessUtil.kt`.
- `DashboardViewModel` is fully common and platform-agnostic already. Nothing in it
  blocks mobile; once a platform provides a real runner + processor, the whole
  pipeline (progress, ETA, resume index, dedupe) comes for free.

---

## 2. Code review findings (correctness)

Ranked. F1–F4 are Phase 2 blockers; the rest are fix-alongside.

**F1 — Android folder picker discards the user's choice.**
`MainActivity.kt:39-44`: launches `OpenDocumentTree`, then ignores the returned URI
and always hands back `getExternalFilesDir(null)`. The user "picks" a folder and
silently gets a different one. Also never takes a persistable URI permission.
→ Resolution is a storage-model decision, not a patch (see §4 D1).

**F2 — Picked ZIPs are copied to `cacheDir` with timestamped names.**
`MainActivity.kt:46-54` + `copyUriToInternalStorage`: every pick copies each
multi-GB ZIP into `cacheDir` under `snapchat_export_<millis>_<i>.zip`.
Consequences: (a) re-picking the same files duplicates gigabytes; (b) the OS may
purge `cacheDir` mid-run; (c) resume/skip logic keys off paths that change every
pick; (d) the copy runs on the main thread inside the activity-result callback —
guaranteed ANR for real exports. → Stream directly from the content URI instead of
copying (see §4 D2), or at minimum copy on `ioDispatcher` to `filesDir` with
content-derived stable names.

**F3 — Duplicate picker implementations on Android.**
`AndroidPickers.kt` has an `actual rememberPlatformPickers()` that is a pure no-op,
while `MainActivity` builds its own anonymous `PlatformPickers`. Two sources of
truth; the no-op is a trap for any future call site. → Move the real
launcher-based implementation into `rememberPlatformPickers()` and have
`MainActivity` use it.

**F4 — The Android preview banner overpromises.**
`DashboardScreen.kt:110` says "ZIP extraction and GPS tagging for images work."
With `NoOpZipPipelineRunner` and stubbed `ZipReader`, nothing works — Start runs a
pipeline that instantly "succeeds" having done nothing. → Until Phase 2 lands:
change the banner to say the Android build is a UI preview, and disable Start on
Android (`isAndroidBuild`) so users can't run a fake sync.

**F5 — Thumbnail LRU cache is bypassed by the entire UI.**
`LibraryScreen.kt` (MediaCard :745, InspectorItemDetail :329, MediaPreviewDialog
:617) call `loadThumbnail(...)` directly inside `produceState`; only desktop
`VideoPlayer` uses `getCachedThumbnail`. Every LazyGrid recycle re-decodes the
image from disk — this is why fast scrolling hitches, and it will be far worse on
phones. Also uses `Dispatchers.Default` for disk I/O instead of `ioDispatcher`.
→ One-line fix at each site: `getCachedThumbnail(item.id)` on `ioDispatcher`.

**F6 — Android `VideoPlayer` restarts playback on every recomposition.**
`androidMain/ui/VideoPlayer.kt:23-26`: the `update` lambda re-sets the URI and
calls `start()` again each time Compose recomposes the view — the video restarts
from zero whenever anything nearby changes state. → `update` should be a no-op
unless `videoPath` changed (remember the last path). Longer term: Media3
`ExoPlayer`, which is also needed for the overlay combine work.

**F7 — `GetMultipleContents("application/zip")` misses common ZIP MIME types.**
Some providers report `application/x-zip-compressed` or
`application/octet-stream`; those exports become unpickable. →
`OpenMultipleDocuments` with a MIME array, plus `GetContent` fallback. Same for
`text/html` (Snapchat's export HTML sometimes arrives as `application/octet-stream`).
Minor: `uris.indexOf(uri)` in the callback is O(n²) and wrong for duplicate URIs —
use `mapIndexedNotNull`.

**F8 — `AndroidMediaProcessor` gaps.**
- `checkExifTool() = true` makes Settings show "ExifTool — Detected" on a platform
  that has no exiftool. Truthful but mislabeled; see U7.
- `formatToExifDate` re-implements date parsing that already exists in the common
  pipeline, and silently drops the date if the format differs.
- `writeDateMetadata` returning false means the ZIP pipeline would report every
  date write as failed once wired up. It's the same `ExifInterface` call as the
  GPS path — implement it in Phase 2 (images), and use file mtime for videos until
  a real MP4 metadata writer exists.
- `exif.setGpsInfo(null)` is a no-op (androidx returns early on null) — delete it.

**F9 — Layout override can strand phone users in an unusable desktop layout.**
Settings → Layout → "Expanded" on a 360 dp phone renders the desktop
sidebar/two-pane UI, and the selector to undo it may be off-screen/cramped. →
Either drop "Expanded" from the choices on touch platforms, or make Settings
itself always render compact-safe (U5 fixes this properly).

**F10 — `MainActivity` picker callbacks don't survive process death.**
The `onXResult` lambdas live in `remember` state; if the OS kills the app while
the picker is open (common on low-RAM phones with a huge Files app in front), the
result is dropped on relaunch. Low priority, but the fix falls out of F3
(registering via `rememberLauncherForActivityResult` in one place, state hoisted
to the ViewModel).

---

## 3. UI/UX review — "does it look clunky?"

Honest answer: the **shell** (bottom nav, theme, cards) is fine — the problem is
that all three screens are desktop layouts being squeezed into a phone, plus a
density/touch-target problem that makes everything feel fiddly on touch. Nothing
here needs a redesign of the visual language; it needs per-screen compact
variants and bigger touch surfaces.

### U1 — Dashboard is a two-column desktop layout on phones (worst offender)
`DashboardScreen` is a hard-coded `Row` with a 40/60 split. On a 360 dp phone the
controls column is ~140 dp wide: mode toggle buttons wrap/truncate, the file
picker boxes ellipsize into uselessness, and the status panel squeezes the
progress ring and 4-step stepper into ~200 dp. This is the screen that made the
whole app feel clunky.

**Recommended compact layout** (single scrolling column, order matters):
1. **Status hero** at top: progress ring (smaller, ~80 dp) with the current step
   as text beneath it — replace the 4-circle stepper with a single line
   ("Step 2 of 4 — Syncing") on compact; the stepper row needs ~320 dp minimum
   and communicates nothing extra.
2. **Source & destination card** (existing card content works, full width).
3. **Pipeline options** collapsed by default (already collapsible — good).
4. **Sticky Start/Stop bar** pinned above the bottom nav (Scaffold
   `bottomBar`-adjacent or a `Surface` with elevation at the bottom of the
   screen), so Start is always reachable without scrolling. This is the single
   biggest usability win.
5. **Logs**: on compact, don't inline-expand a 200 dp terminal into the scroll
   column — open a `ModalBottomSheet` (or full-screen route) with the log list +
   copy button.

Mechanically: split `DashboardScreen` into `DashboardControls(...)` and
`DashboardStatus(...)` composables (state unchanged, all hoisted in the
ViewModel already), then `DashboardScreen` picks `Row(40/60)` vs `Column` from
`WindowSize`. No logic changes.

### U2 — Library's fixed 280 dp inspector makes the phone grid ~80 dp wide
`LibraryScreen` always renders the right-hand inspector `Surface(width(280.dp))`.
Inside `PhoneRoot` that leaves ~80 dp for the grid — one crushed column of cards.
This screen is effectively broken on phones today.

**Recommended compact layout:**
- Drop the inspector pane entirely on compact. Tap on a card → full-screen
  preview (existing `MediaPreviewDialog` already works well on phones since it's
  `fillMaxSize`-ish) with the inspector's metadata rows merged into its info bar,
  or a `ModalBottomSheet` showing `InspectorItemDetail`.
- Stats chips row (4 chips + refresh) doesn't fit 360 dp → put the chips in a
  `LazyRow` (horizontal scroll) or collapse to "128 memories · 90 photos · 38
  videos" one-liner.
- Filter tabs + 200 dp search field don't fit on one row → stack: tabs row, then
  full-width search field (or a search icon that expands).
- Grid: `GridCells.Adaptive(160.dp)` gives 2 columns on phones — that's fine.
  Consider `Adaptive(110.dp)` on compact for a denser 3-column photo-app feel.

### U3 — Medium size class (600–840 dp: tablets-portrait, foldables, split-screen) is unhandled
`getActiveWindowSize` returns `Medium` but `App.kt` only branches on `Compact`,
so Medium gets the full desktop treatment: sidebar (~200 dp) + 40/60 dashboard,
or sidebar + grid + 280 dp inspector — three panes in 700 dp. That's the "tablet
clunk" you're anticipating.

**Recommendation:** on Medium, use a `NavigationRail` (icons-only, ~80 dp)
instead of the sidebar, and give each screen its compact single-pane variant
except Library, which can keep the inspector (rail 80 + grid ~340 + inspector 280
fits). This gets tablets a purposeful layout for ~30 lines of code:
`Compact → bottom nav`, `Medium → rail`, `Expanded → sidebar`, with the per-screen
compact/expanded variants from U1/U2 reused.

### U4 — Touch targets and type are desktop-dense
Recurring across all screens: 9–11 sp text, 13–16 dp icons, `clickable` on bare
`Text` ("Clear", "Refresh Status", theme/layout segment cells, log copy icon).
Material minimum touch target is 48 dp; most of these are under 30 dp. On desktop
with a mouse this reads as "dense pro tool"; on a phone it reads as clunky and
misses taps.

**Recommendation (cheap, high value):** don't restyle the app — add
`Modifier.defaultMinSize(minHeight = 44.dp)` / larger padding to interactive
elements **when `WindowSize == Compact`**, and bump the smallest text (9–10 sp →
12 sp) on compact. A tiny `LocalWindowSize` CompositionLocal (provided from
`App.kt`) lets any component ask which size class is active without threading
parameters everywhere. Long-term: migrate hard-coded `sp` to
`MaterialTheme.typography` roles, but that's polish, not launch-blocking.

### U5 — Settings rows overflow on narrow widths
Theme and Layout rows are `icon + label-column(weight) + segmented control` on one
line. At 360 dp the three-segment control ("System/Light/Dark",
"Auto/Compact/Expanded") squeezes the label to nothing or clips. → On compact,
stack: label + description, then the segmented control full-width beneath it.
Same for the ExifTool/FFmpeg `Row` of two `DependencyItem(weight(1f))` cards —
stack them vertically on compact.

### U6 — Dead affordances in the top bar
`AppTopBar` renders search, notifications, and help icons that do nothing on any
platform. On desktop they're quiet decoration; on a phone every pixel of a 56 dp
bar counts and dead icons are classic clunk. → Remove them (all platforms — it's
honest UI) and on compact consider dropping the top bar entirely: the phone
layout already has bottom nav for identity/navigation, and brand + version can
live in Settings. That returns 56 dp of vertical space to every screen.

### U7 — Platform-truthful wording
- Settings "System Dependencies" on Android shows "ExifTool — Detected" (it's
  `ExifInterface`) and "FFmpeg — Missing" with a desktop install hint. On mobile
  this section should describe capabilities, not binaries: "Photo metadata —
  Supported", "Video processing — Not yet available".
- The Android preview banner (F4) should state exactly what works, and disappear
  once Phase 2 makes it a lie in the other direction.

### U8 — Small compact-mode paper cuts
- `PhoneNavItem` labels are `10.sp` — NavigationBar's default label style is
  fine; drop the override.
- `MediaPreviewDialog` close button is 28 dp — enlarge on compact (U4 covers it).
- Keyboard: `SettingsScreen`/`LibraryScreen` search — verify `adjustResize` +
  `imePadding()` so the keyboard doesn't cover the search field on Android.
- Check `NavigationBar` inset handling: `App.kt` already consumes system bars via
  `windowInsetsPadding(WindowInsets.systemBars)`; make sure the nav bar doesn't
  double-pad on devices with gesture nav (visual check on device).

---

## 4. Storage-model decisions (Android) — decide before Phase 2 code

**D1 — Output folder.** Options:
1. **App-private `filesDir/SnapVault` + "Export to Photos" via `MediaStore`**
   *(recommended)* — no permissions, no SAF weirdness, pipeline code keeps using
   `java.io.File` paths unchanged; add an explicit "Save all to Photos" action
   (MediaStore inserts show up in Google Photos/Gallery immediately, and
   `DATE_TAKEN`/EXIF survive).
2. SAF `OpenDocumentTree` honestly (fix F1) — user picks any folder, but then the
   entire pipeline must speak `DocumentFile`/content URIs instead of `File`
   paths, which touches everything. Not worth it for v1.

**D2 — ZIP input.** Options:
1. **Read directly from the content URI** *(recommended)*: implement the Android
   `ZipReader`/extract actuals on `ZipInputStream(contentResolver.openInputStream(uri))`
   instead of file paths. No copy at all — fixes F2 outright. Cost: the
   `expect fun`s take a string; content URIs are strings, so `ZipInputStream`-based
   Android actuals can branch on `content://` prefix. Note: `ZipInputStream` is
   stream-only (no central directory), which is fine for our sequential
   extract-all pattern but makes `listZipEntries` a full scan per call — cache the
   listing per URI.
2. One-time copy into `filesDir/imports/<hash>.zip` with stable content-derived
   names, on `ioDispatcher`, with progress UI. Simpler code (java.util.zip
   `ZipFile` works as-is), costs 2× disk transiently. Acceptable fallback if (1)
   fights back.

Either way: delete `copyUriToInternalStorage` from the picker path.

---

## 5. Phase 2 — Android functional (ordered)

Do these as small PRs in this order; each step leaves the app better than before.

**2.0 Honesty patch** (can ride with any early PR): F4 banner + disable Start on
Android until 2.3 lands; F5 thumbnail cache fix; F6 video restart fix. All tiny.

**2.1 Move the JVM zip stack to `jvmSharedMain`.**
- Move `desktopMain/ZipReader.kt` → `jvmSharedMain` (delete the Android stub).
  This alone lights up ZIP parsing + the precise time/GPS matcher on Android.
- Move `downloader/ZipExtractEngine.kt` → `jvmSharedMain`.
- Split `DesktopZipPipelineRunner`: the extract/dedupe half is pure
  `java.util.zip`/okio → `jvmSharedMain` as `JvmZipPipelineRunner`; the
  combine-with-ffmpeg half stays desktop (Android's combine comes in 2.5).
- Desktop tests (65) must stay green — they are the regression net for this move.

**2.2 Real Android pickers + storage model.**
- Implement D1/D2 decisions. Real `rememberPlatformPickers()` (F3, F7), delete
  the `MainActivity` anonymous object, drop the fake folder picker (F1) —
  output folder becomes app-private and the "output" picker button on Android
  either disappears or opens the export flow.

**2.3 Wire the real pipeline.**
- `MainActivity` passes `JvmZipPipelineRunner` instead of `NoOpZipPipelineRunner`.
- `AndroidMediaProcessor.writeDateMetadata` via `ExifInterface` for JPEG; for MP4
  set file mtime (and record a TODO for real MP4 tags — `mp4parser` or Media3
  metadata editing later).
- Reuse the pipeline's existing date parsing instead of `formatToExifDate` (F8).
- **Milestone: a real Snapchat export imports end-to-end on a phone.** Test with
  your own multi-ZIP export.

**2.4 Library on Android.**
- `scanMediaFiles`: same logic as desktop `MediaScanner` (it's `java.io.File` —
  check if it can simply move to `jvmSharedMain` with the thumbnail decode split
  out, since decode is the only Skia/AWT-vs-Android difference).
- `loadThumbnail`/`loadFullImage` via `BitmapFactory` with `inSampleSize`
  downsampling (never decode a 12 MP photo for a 160 dp cell) →
  `Bitmap.asImageBitmap()`.

**2.5 Image overlay combine on Android.**
- Pure `android.graphics`: decode main + overlay, draw overlay scaled onto a
  `Canvas(mainBitmap.copy(...))`, re-encode JPEG, then re-write EXIF (combine
  before metadata write in pipeline order — the common pipeline already orders
  this correctly). Video combine stays "not yet" (banner says so) — fast-follow
  with Media3 `Transformer` + overlay effect after launch.

**2.6 UI: compact layouts (§3).**
- U1 Dashboard split + sticky Start; U2 Library inspector removal on compact;
  U5 Settings stacking; U3 Medium/NavigationRail; U4 touch targets; U6 top bar.
  This can proceed in parallel with 2.1–2.5 (it's all commonMain).

**2.7 Launch prep (Android).**
- Real app icon (adaptive icon, reuse the desktop `ic_launcher` art).
- `versionCode`/`versionName` wired to the release workflow; R8 enabled with
  keep-rules verified (`ExifInterface`, compose-resources); targetSdk current.
- Play Console ($25): privacy policy page (the README privacy section can be
  hosted via GitHub Pages), data-safety form ("no data collected, all local" —
  Snapchat downloads happen device-side), internal testing track first.

## 6. Phase 3 — iOS functional (after Android ships)

Same shape, different actuals; the common pipeline and the §3 UI work carry over.

- **3.1 ZIP reading**: no `java.util.zip`. Options: cinterop to system zlib +
  minimal central-directory parser (we already parse the 0x5455 extra field on
  desktop — same format), or vendor libzip via cocoapods/SPM. The three
  `ZipReader` actuals + an extract engine are the whole surface.
- **3.2 Pickers**: `UIDocumentPickerViewController` (zip + folder via
  security-scoped bookmarks), present from the root VC (`MainViewController`
  needs a hook to the top controller).
- **3.3 Metadata**: images via ImageIO (`CGImageSourceCopyProperties` +
  `CGImageDestination` with updated GPS/date dicts); videos via `AVAssetExportSession`
  metadata; overlay combine via CoreGraphics.
- **3.4 Library**: `scanMediaFiles` via `NSFileManager`; thumbnails via ImageIO
  `CGImageSourceCreateThumbnailAtIndex` (fast, downsampled by design).
- **3.5 Ship**: Apple Developer account ($99/yr — the same one unlocks macOS
  Developer ID signing/notarization from `docs/CODE_SIGNING.md`), bundle
  ID/entitlements already in `iosApp/`, TestFlight first.

## 7. Suggested PR sequence

| PR | Contents | Depends on |
|---|---|---|
| A | 2.0 honesty patch + F5 + F6 + U8 nits | #18 merged |
| B | 2.1 zip stack → `jvmSharedMain` | #18 |
| C | 2.2 pickers + storage (D1/D2 decided) | B |
| D | 2.3 real pipeline + date writes — *the milestone PR* | B, C |
| E | 2.6 compact/medium UI rework (U1–U6) | #18 (parallel-safe) |
| F | 2.4 Library scan/thumbnails | B |
| G | 2.5 image overlay combine | D |
| H | 2.7 icon + release wiring + Play internal track | D–G |

Open decisions to settle before PR C: **D1** (app-private + MediaStore export
vs SAF) and **D2** (stream from URI vs stable-name copy). Recommendations are
marked; both recommended paths keep the common pipeline untouched.
