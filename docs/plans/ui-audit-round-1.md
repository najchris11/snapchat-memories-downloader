# UI Audit — Round 1

Fixes for 13 findings on branch `fix/ui-audit-round-1` off `develop`: 11 of the 38 in the UI audit,
plus two found while working (items 12 and 13).

Scope: the sub-option alignment bug, the import-mode reorder, the three blockers, and the quick wins.
Deferred to a later round: the Typography migration (T1/T2, 86 `fontSize` literals), the bulk string
extraction (S1), and the remaining cleanup items.

Testing policy for this work and everything after it is in `AGENTS.md` / `CLAUDE.md`: a behavioural
change lands with a test that fails without it, and the test is verified to fail by deliberately
reintroducing the bug.

Ordered so each item is independently testable and the risky one (N3) lands last.

---

## 1. Sub-option rows are indented but their toggles are not

**Severity:** Fix · **Files:** `DashboardScreen.kt`

The two nested pipeline options — "Precise time + GPS matching" and "Preview only" — are wrapped in
`Box(modifier = Modifier.padding(start = 26.dp))`. The child `PipelineItem` is `fillMaxWidth()` with
`Arrangement.SpaceBetween`.

Mechanism: the `Box` receives the parent `Column`'s max-width constraint. The start padding shrinks
the child's constraint to `max − 26`, the child fills that, and the `Box` then reports
`26 + (max − 26) = max`. So the icon and label shift 26 dp right while the `Switch` stays pinned to
the same right edge as every sibling row. The gap between label and toggle is 26 dp wider than any
other row in the card, which reads as a stray tab or spacer rather than as nesting. It also leaves a
26 dp dead strip on the left of an otherwise full-width click target.

**Fix:** delete both `Box(modifier = Modifier.padding(start = 26.dp))` wrappers so every row in the
card shares one alignment grid — "Precise time + GPS matching" lines up with "Write Date Metadata",
"Preview only" with "Clean Duplicate Files". Labels align, toggles align, and the click target is
full-width again.

No compensating indent, rule, or recessed panel. Two reasons: a recessed sub-panel would put a third
nested container inside `ControlCard`, which is already a translucent `surface.copy(alpha = 0.45f)`
over the app background — a fourth alpha layer under 13 sp text, the exact problem flagged as T3.
And muting the child labels would mean adding yet another `.copy(alpha = …)`, which contradicts T3
directly. The conditional appearance of these rows plus their own copy ("Preview only (dry run —
nothing deleted)") already carries the subordination.

Also in this block, since it is the same three lines:

- Line 309's label is a hardcoded string that reads like a developer note — *"Precise time + GPS
  matching (experimental, can be turned off)"*. The "can be turned off" half describes the switch
  sitting next to it. Extract to `opt_precise_matching` as **"Precise time + GPS matching"**,
  dropping the experimental caveat: the matcher was verified at 99.7 % on a real 10,151-memory
  export, correctly omitting GPS on all 198 timestamp collisions rather than guessing. The toggle
  stays, so users can still turn it off. The README's experimental note needs the same edit.
- ~~`experimentalMetadataMatching` stays `true` when the user switches to Legacy mode; reset it when
  the import mode changes.~~ **Dropped after checking the ViewModel.** The flag is consumed only
  inside `runZipPipeline`, so it cannot affect a Legacy run, and it is never surfaced in Legacy mode.
  Resetting it would discard a deliberate "off" choice whenever the user toggled modes and came
  back — strictly worse than the harmless retention. No change.

Deliberately **not** removing the toggle or the `experimentalMetadataMatching` parameter from
`startSync` / the ViewModel — this is a wording change only.

**Verify:** all rows' labels share one left edge and all toggles one right edge; clicking anywhere on
a former sub-row toggles it; no "experimental" wording remains in the UI or README.

---

## 2. Import mode order — ZIP first, Legacy second

**Severity:** Fix · **Files:** `DashboardScreen.kt`

The Source & Destination card presents "Legacy (HTML/JSON)" as the first of the two mode toggles and
"ZIP Import" as the second. That inverts the recommendation: the README calls ZIP import the
recommended path, it is the only mode that reads the raw export archives directly, and it is the mode
the full-export verification was run against. First position reads as the default.

**Fix:** swap the two `ModeToggleButton` calls so ZIP Import is first. Both labels are hardcoded
literals — extract them to `opt_mode_zip` and `opt_mode_legacy` while the lines are being touched.
Leaves `ImportMode`'s declaration order and the ViewModel's default alone; this is presentation only.

**Verify:** ZIP Import sits on the left and is still the selected mode on a fresh launch; selecting
Legacy still switches the card to the history-file picker.

---

## 3. N1 — Duplicate deletion is on by default, behind a collapsed card

**Severity:** Blocker · **Files:** `DashboardScreen.kt`

`pipelineExpanded = false`, `runDedupe = true`, `dryRun = false`. A user who picks a source and an
output folder and presses **Start Download** has consented to deleting files without seeing the
option or the dry-run switch that exists to make it safe. The comment at line 316 —
*"Dedupe deletes files — give it a preview mode"* — shows the intent; the disclosure default undoes it.

**Fix (both halves):**

- `dryRun = true` by default, so deletion is an explicit opt-out rather than an unseen default.
- `pipelineExpanded = true` by default, so every enabled step is visible before Start.

Expanding the card by default pushes the Start button further down the scrolling column, which makes
item 4 (N8) a prerequisite rather than a nice-to-have — do them together.

**Verify:** fresh launch shows the options expanded with "Preview only" on; a dedupe run with
defaults deletes nothing and logs what it *would* have deleted.

---

## 4. N8 — The Start button can scroll out of view

**Severity:** Fix · **Files:** `DashboardScreen.kt`

The button row sits at the bottom of a `verticalScroll` column, below both cards. With the Pipeline
card now expanded by default (item 3), the primary action scrolls off the bottom on a short window
with nothing pinning it.

**Fix:** lift the button row out of the scrolling column into a sticky footer beneath it, so the
scroll region is the cards only. Left column becomes
`Column { Column(weight(1f).verticalScroll()) { cards }; buttonRow }`.

**Verify:** shrink the window to ~600 dp tall — Start and Stop stay visible while the cards scroll.

---

## 5. N5 — A failed run renders as a successful one

**Severity:** Blocker · **Files:** `DashboardViewModel.kt`, `DashboardScreen.kt`, `strings.xml`

`hasWarnings` has exactly one consumer: it tints step 4's 30 dp circle amber. No count, no message,
no pointer to the log — and the log panel is collapsed by default. This is the finding that bit us
during the full-export verification: 24,885 failures reported as *"Complete"*.

The data already exists. `pipelineFailureCount` (`DashboardViewModel.kt:80`) accumulates failures
across every phase — extraction, metadata, combine, dedupe, downloads — and is reset per run at
line 129. It is `private`.

**Fix:**

- Expose the count as public read-only state alongside `hasWarnings`.
- On the Dashboard, when `hasWarnings` is set, render a persistent warning row under the progress
  text: the failure count, a one-line summary, and a button that expands the log panel. Use
  `SnapVaultColors.warning`, matching the step-4 tint so the two read as the same signal.
- New strings: `status_completed_with_warnings` (`"%d step(s) failed"`), `btn_view_details`.

**Verify:** a run with a deliberately unwritable output folder shows the failure count and the
log-expanding button on completion, not a clean "Complete".

---

## 6. S3 — The desktop window has no title

**Severity:** Fix · **Files:** `Main.kt`

`window_title` is defined in `strings.xml` and referenced nowhere: the `Window(…)` call passes
`onCloseRequest`, `state`, `undecorated`, `transparent` and `icon`, but no `title`. The window is
undecorated so there is no visible titlebar — which is why this went unnoticed — but the taskbar
entry, the alt-tab card and the window manager all read that property.

**Fix:** `title = stringResource(Res.string.window_title)`.

**Verify:** alt-tab and the taskbar show "SnapVault — Snapchat Memories Downloader".

---

## 7. C2 + C3 — Three colours that defeat the light theme

**Severity:** Fix · **Files:** `Theme.kt`, `DashboardScreen.kt`

Three sites, all in the log terminal:

- `DashboardScreen.kt:544` — `Text("$ ", color = ElectricPurple)` uses the raw top-level `val`, a
  fixed dark-mode hex, instead of the theme-aware `SnapVaultColors.electricPurple`.
- `DashboardScreen.kt:717` — `"SKIP" -> SecondaryBlue.copy(alpha = 0.85f)`, same problem.
- `DashboardScreen.kt:530` — the log surface is `Color.Black.copy(alpha = 0.45f)` while its text is
  `onSurface`, which is near-black `#0D1525` in light theme. Dark text on a dark grey panel.

**Fix:** the log panel is deliberately a terminal, so make that explicit rather than accidental —
add a `terminalSurface` / `onTerminal` pair to `SnapVaultColors` that stays dark in both themes and
carries a light foreground with it. Point the panel, its text and its tag colours at those. Swap the
two raw constants for theme-aware accessors, adding a `muted` role for the skip tag. Then mark the
eleven public palette `val`s in `Theme.kt` `internal` so this cannot recur.

**Verify:** switch to the light theme with the log expanded — panel and text both legible, prompt
glyph and `[SKIP]` tags correct in both themes.

---

## 8. N2 — Three top-bar actions that are not actions

**Severity:** Fix · **Files:** `TopBar.kt`, `App.kt`, `strings.xml`

Search, Notifications and Help are bare `Icon` composables with no `onClick` — no `IconButton`, no
wrapper. They carry `contentDescription`s, so assistive tech announces three controls that do not
exist, and sighted users get three affordances styled exactly like the window controls beside them.

**Fix:** wire Help to the repository docs
(`https://github.com/najchris11/snapchat-memories-downloader#readme`) as a real `IconButton`, using
the same platform-open helper pattern as `VideoPlayer.openVideoLocally`. Delete Search and
Notifications — there is nothing to search and nothing to notify, and a missing control beats a
lying one. Retire `topbar_search` and `topbar_notifications` from `strings.xml`.

**Verify:** Help opens the docs in a browser; the other two icons are gone; Help has a 48 dp target
and a visible focus state.

---

## 9. N4 — No route from Dashboard to Settings, and missing dependencies are never surfaced

**Severity:** Fix · **Files:** `DashboardScreen.kt`, `App.kt`, `PhoneRoot.kt`, `strings.xml`

`DashboardScreen` declares `onNavigateToSettings: () -> Unit` and both roots supply it
(`App.kt:110`, `PhoneRoot.kt:84`). It is never referenced in the function body.

The consequence is concrete: ExifTool and FFmpeg are probed at launch, and the only place that
reports a miss — along with `binaryInstallHint()`, the platform-specific install instructions — is
the Settings screen. The Dashboard never mentions it. A user without FFmpeg gets a run where every
overlay silently fails to combine and no prompt that leads to the explanation.

**Fix:** pass `hasExifTool` / `hasFFmpeg` into `DashboardScreen` and show a banner when either is
missing, naming the tool and what it costs them ("Overlays will not be combined"), with the existing
`onNavigateToSettings` as its action. Reuse the Android-preview banner's shape at
`DashboardScreen.kt:85–116` so there is one warning-banner treatment rather than two.

**Verify:** with FFmpeg renamed out of the way, the Dashboard shows the banner and the action lands
on Settings; with both present, no banner.

---

## 10. D1 — A working video player exists on three platforms and is never called

**Severity:** Fix · **Files:** `LibraryScreen.kt`

`VideoPlayer` is `expect`-declared in `commonMain` with `actual` implementations in `desktopMain`,
`androidMain` and `iosMain`. The desktop one is complete — cached thumbnail, hover state, play
button, "Opening Video…" transition, and `Desktop.getDesktop().open()` with `xdg-open` / `open`
fallbacks. Nothing in the app references it. Meanwhile `MediaPreviewDialog` renders the literal
string *"Video preview not available"* for every video, in the exact place it was written for.

**Fix:** replace the placeholder branch in `MediaPreviewDialog` with `VideoPlayer(item.id)`. Highest
value-per-line change in the audit — roughly a third of the library is video, and it goes from
"not available" to playable.

**Verify:** open a video from the Library grid — thumbnail and play button render, clicking opens it
in the system player.

---

## 11. N3 — The compact layout changes the navigation but not the screens

**Severity:** Blocker · **Files:** `App.kt`, `PhoneRoot.kt`, `LibraryScreen.kt`, `DashboardScreen.kt`

Largest item; landing last because it touches both screens' top-level structure.

`PhoneRoot` replaces the sidebar with a bottom `NavigationBar` and then renders the identical
desktop screens, which are built from fixed widths and horizontal splits with no compact branch.

- **Library is unusable.** A `Row` of an adaptive grid (`minSize = 160.dp`) plus a hard `280.dp`
  inspector, inside `24.dp` padding. At a 400 dp phone width the grid receives roughly 72 dp — less
  than half of one cell.
- **Dashboard clips.** A 40/60 `Row`; the four-step stepper needs about 270 dp (four 30 dp circles,
  three 36 dp dividers, plus labels) but receives `0.6 × (width − 72dp)`. Below roughly 520 dp the
  stepper squashes or overflows.
- `WindowSize.Medium` is computed and discarded — `App.kt:86` only branches on `== Compact`, so
  600–840 dp gets the full desktop layout including both fixed panels.

**Fix:** thread `windowSize: WindowSize` into both screens rather than a `compact: Boolean` — the
boolean cannot express Medium, which needs the sidebar but not a second fixed panel. Explicit
parameter beats a `CompositionLocal` here: two call sites, and it stays visible in the signature.

- `LibraryScreen(compact = true)`: drop the inspector column entirely; tapping a card opens the
  existing `MediaPreviewDialog`, which already carries the same metadata. Make the stat-chip row
  horizontally scrollable. Reduce padding from 24 dp to 16 dp.
- `DashboardScreen(compact = true)`: stack the two columns into one scrolling `Column`. Replace the
  four-circle stepper with a compact progress row — "Step 2 of 4 · Downloading" plus the existing
  ring — which also fixes the clipping at Medium widths.
- Decide `Medium` deliberately rather than falling through: sidebar plus Library grid, no inspector
  column. One extra branch in `App.kt`.

**Tests:** `WindowSizeClassTest` pins the bucket boundaries (599/600 and 839/840) and asserts
Medium is distinct from both neighbours, so it cannot silently collapse into Expanded again.
`LibraryScreenTest` asserts the inspector is present at Expanded and absent at Medium and Compact,
and that the empty state offers a folder picker at every width. Verified by reverting
`showInspector` to `true`: exactly `compactDropsTheInspectorPanel` and
`mediumDropsTheInspectorPanelToo` fail, and no others.

Follow-up audit found that Dashboard still selected its two-column layout at Medium even though the
plan called for the compact progress treatment there. `DashboardScreenTest.mediumUsesTheStackedDashboardLayout`
now pins Medium to the stacked layout; it was observed failing against the original Compact-only
condition before that condition was fixed.

Two things fell out of the restructure that are worth recording:

- `DashboardScreen` had to be split into `DashboardControls`, `DashboardActions` and
  `DashboardStatus`, because the two layouts compose the same three pieces in a different order.
  Option state moved into a `PipelineOptions` holder rather than passing seven `var`s down.
- The status panel contained two `Spacer(Modifier.weight(1f))`. A weight spacer needs a bounded
  height, and in the compact layout that panel sits inside a `verticalScroll` where height is
  infinite — so both are now conditional. This would have been a runtime failure, not a cosmetic
  one.

**Verify by eye:** resize from 1280 dp down to 380 dp — no horizontal overflow, no clipped stepper,
Library grid always at least two columns.

---

## 12. Thumbnail cache was wired to nothing

**Severity:** Fix · **Files:** `LibraryScreen.kt`

`ThumbnailCache` is a 150-entry LRU in `MediaScanner.kt`, reached through
`getCachedThumbnail()`. Its only caller was the `VideoPlayer` that was never invoked (item 10),
so the cache had never run in the app at all. `MediaCard`, `InspectorItemDetail` and
`MediaPreviewDialog` each called `loadThumbnail()` directly — meaning every grid tile re-read
and re-decoded its thumbnail from disk on each recomposition, with no in-memory reuse across a
10,000-item library.

All three now go through `getCachedThumbnail()`. They also move from `Dispatchers.Default` to
`ioDispatcher`: `loadThumbnail` shells out to ffmpeg for video frames and runs ImageIO decodes
for stills, which are blocking calls that do not belong on the CPU-bound pool.

**Tests:** `ThumbnailCacheTest` — six cases covering store/hit/miss, replace-in-place, clear,
eviction at capacity, and the LRU property specifically (a read must move an entry to newest,
or the cache degrades into FIFO and evicts tiles the user is actively looking at). None of
this behaviour had ever executed before, let alone been tested.

---

## 13. `IS_DEBUG` flips on the Gradle task name

**Severity:** Fix · **Files:** `composeApp/build.gradle.kts`

Surfaced by a crash while running the app:

```
java.lang.NoClassDefFoundError: com/najdev/snapvault/DraggableAreaKt$DraggableArea$2$1$1
  at com.najdev.snapvault.DraggableAreaKt$DraggableArea$2$1.invoke(DraggableArea.kt:31)
```

A synthetic lambda class was missing from `build/classes` while its enclosing class was
present — the loaded outer class came from a different compilation than its inner classes.
That is stale incremental-compilation output, not a source defect: `DraggableArea.kt` is
untouched by this branch, and all three class files are present and consistent now.

**Not reproducible on demand.** Six alternating `isDebug` flips did not drop the class, so
the mechanism below is a contributing factor, not a proven proximate cause. Recorded honestly
rather than asserted.

What *is* verified is the design problem:

```kotlin
val isDebugBuild: Boolean =
    (project.findProperty("isDebug") as? String)?.toBoolean()
        ?: gradle.startParameter.taskNames.any { it == "run" || it.endsWith(":run") }
```

`IS_DEBUG` is generated into `AppBuildConfig.kt`, a **commonMain source file**, as a
`const val` — so it is inlined at every use site, and changing it invalidates every file that
reads it. Because the value is derived from *which Gradle task you invoked*,
`:composeApp:run` produces `IS_DEBUG = true` while `compileKotlinDesktop` and `desktopTest`
produce `false`. Alternating between running the app and running tests therefore rewrites a
commonMain source and forces a broad recompile every single time — which is exactly the
condition under which this class of staleness appears.

**Immediate remedy:** `./gradlew clean`, then run.

**Fix:** make `IS_DEBUG` a plain `val` rather than a `const val`, so a change recompiles only
`AppBuildConfig.kt` instead of every consumer. Optionally drop the task-name inference and
require `-PisDebug=true` explicitly, so `run` and `test` stop producing different builds.
Worth doing regardless of whether it was the proximate cause of this crash: the 2,500-item
debug import cap silently turning on because of how a task was named is its own hazard.

---

## Rounds 2 and 3

Everything else from the audit is planned in `ui-audit-round-2.md` (the design system:
typography, contrast, one accent, real controls, semantics, keyboard, detekt) and
`ui-audit-round-3.md` (strings and localisation, information architecture, and the three
feature builds — sort, reveal-in-file-manager, favourites).

## Not in this round

Tracked in the audit, deliberately deferred:

| Finding | Why deferred |
|---|---|
| T1, T2 | 86 `fontSize` literals → a real `Typography`. Mechanical, wide, and better reviewed on its own. |
| S1 | ~40 hardcoded strings. Partially addressed here (items 1, 4, 7, 8 add their own resources); the bulk sweep is its own pass. |
| C1 | Two competing accents. Wants a decision on which violet wins, and touches every screen. |
| N6 | Two `currentScreen` states. Cheap, but overlaps item 10's changes to both roots — do it after. |
| C4–C6, S2, S4–S6, N7, N9–N12, D2–D6, T3–T8 | Cleanup and lower-severity fixes. |

## Sequencing

Items 1 → 10 plus 12 and 13 are independent and can land as separate commits in any order. Item 3
requires item 4. Item 11 lands last. Run `./gradlew :composeApp:compileKotlinDesktop` and the existing desktop test
suite after each; no UI tests exist yet, so verification is the manual check listed per item.
