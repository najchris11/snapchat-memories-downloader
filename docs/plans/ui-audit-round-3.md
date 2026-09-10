# UI Audit — Round 3: content, data, and the dead ends

Sixteen findings, on a branch off `develop` after round 2 merges.

Rounds 1 and 2 fixed defects and the design system. What is left is everything about
*content*: text that cannot be translated, information architecture that contradicts itself
across layouts, and half-built features that either need finishing or deleting.

Larger than the previous rounds, but the items are individually small and fall into three
phases that can land as separate PRs if it runs long:

- **3a — Strings and localisation** (items 1–6)
- **3b — Information architecture and cleanup** (items 7–13)
- **3c — The three feature builds** (items 14–16)

Testing policy is in `AGENTS.md` / `CLAUDE.md`: a behavioural change lands with a test that
fails without it, verified by deliberately reintroducing the bug.

**Decided:** build the sort control (item 14), reveal-in-file-manager (item 15) and
favourites (item 16). Delete the duration UI (item 12) rather than populating it.

---

## Current state, measured

| | Count |
|---|---|
| Hardcoded user-facing literals in UI files | **35** |
| Orphaned string resources | **12** |
| Hand-built plurals | **5** expressions across 4 lines |
| Locale variants of `strings.xml` | **0** (default only) |

---

# 3a — Strings and localisation

## 1. S1 — The remaining hardcoded strings

**Severity:** Fix · **Files:** all UI files, `strings.xml`

Rounds 1 and 2 extracted strings opportunistically when touching a line. **35** user-facing
literals remain, including every `contentDescription` in the app.

Notable clusters: the ZIP source-mode toggles and file-picker placeholders ("ZIP Folder",
"Pick Files", "ZIP Export Folder", "Select folder containing mydata~*.zip files", "N ZIP files
selected", "+ N more"), the log controls ("View Logs", "Copied!", "Copy logs",
"running_pipeline"), the Library inspector row labels (SIZE / GPS / OVERLAY / DURATION,
"Tagged", "No data", "Combined", "None"), the Library stat chips, and most of Settings
("Manage system dependencies and utility preferences.", theme and layout mode names, "Layout",
"Output Path", "Not set", "Edit", the dependency names and descriptions, the licence footer).

**Fix:** two passes. Visible copy first — that is the consistency win and the bulk of the
count. `contentDescription`s second, which is the accessibility win and pairs with round 2's
item 9.

**Tests:** a resource-coverage test is the wrong shape here (it would assert on source text).
Instead: after the sweep, add a CI grep step that fails on new `Text("` literals in
`commonMain/ui`, so the count cannot silently climb back. Document the allowed exceptions
(format assembly, monospace log content).

---

## 2. S2 — The phone nav re-hardcodes labels the sidebar reads from resources

**Severity:** Fix · **Files:** `PhoneRoot.kt`

`PhoneRoot` passes `label = "Dashboard"`, `"Library"`, `"Settings"` as literals, while
`nav_dashboard`, `nav_library` and `nav_settings` exist and are already used by `AppSidebar`
for the same three destinations. Same navigation, two sources of truth, guaranteed to diverge.

Technically part of item 1, called out separately because it is the one case where the
resource already exists and is already used elsewhere — a pure inconsistency rather than a
gap.

**Tests:** a UI test asserting the sidebar and bottom bar render the same three labels, so a
future edit to one has to touch the other.

---

## 3. S4 — Plurals built by concatenation

**Severity:** Fix · **Files:** `LibraryScreen.kt`, `strings.xml`

Five expressions across four lines compose counts as
`"$n item${if (n == 1) "" else "s"}"` — English-only by construction, and impossible to
localise without rewriting the call site. The two `%d` resources written for this job,
`lib_gps_items` and `lib_overlay_items`, sit unused.

Sites: `LibraryScreen.kt:552` (files), `:567` (photos and videos, two in one line), `:588`
(items tagged), `:594` (assets combined).

**Fix:** Compose Resources supports plural resources. Use them, and fold the two orphaned
`%d` keys into them.

**Tests:** assert the singular and plural forms both render for counts of 1 and 2 — the
off-by-one that hand-rolled pluralisation gets wrong.

---

## 4. S5 — Twelve orphaned string resources

**Severity:** Cleanup · **Files:** `strings.xml`

| Key | Disposition |
|---|---|
| `lib_sort_newest`, `lib_filter_date` | **Revived** by the sort control (item 14) |
| `lib_gps_items`, `lib_overlay_items` | **Folded** into plurals (item 3) |
| `app_tagline`, `dashboard_title`, `library_title` | Delete — the sidebar carries the label; Settings is the only screen with a heading |
| `lib_vault_tools_label`, `lib_tool_export`, `lib_tool_optimize`, `lib_tool_privacy`, `lib_tool_vault_link` | Delete — an inspector section that no longer exists |

Eight deleted, four revived. Do this **after** items 3 and 14, or the four get deleted and
immediately re-added.

**Tests:** none (deletions). The CI grep from item 1 does not cover unused resources; consider
a small script that flags `name="…"` keys with no `Res.string.` reference, run in CI.

---

## 5. S6 — Library's filter state is keyed on raw English literals

**Severity:** Cleanup · **Files:** `LibraryScreen.kt`

`selectedFilter` is a `String` holding `"All"`, `"Photos"` or `"Videos"`. The same literals
drive the filter predicate, the tab list, and a `when` mapping them to display resources — so
the state key and the label are two representations of one thing joined only by convention.
Change the resource and the filter silently stops matching.

**Fix:** `enum class MediaFilter { All, Photos, Videos }` with the resource lookup hanging off
the enum. Pairs naturally with the sort control (item 14), which needs the same treatment.

**Tests:** assert each filter selects the right subset, which the current string-keyed version
cannot be tested for without duplicating the literals.

---

## 6. T8 — Dates render in English regardless of locale

**Severity:** Cleanup · **Files:** `desktopMain/MediaScanner.kt`, `iosMain/MediaScanner.kt`

`formatCaptureDate` builds month abbreviations as `date.month.name.take(3)` — the first three
letters of the Java enum constant, so always `JAN`, `FEB`, `MAR`. `formatFileDate` takes the
other route and pins `Locale.US`. Both produce English; the two paths just get there
differently, and every date in the Library is affected.

Worth noting the comments around both are correct and thoughtful about the harder problem —
the UTC-midnight conversion bug they avoid. Only the locale slipped, so **keep the
`LocalDate`-direct approach** and change only the formatting.

**Tests:** existing `MediaScannerTest` cases assert `"NOV 28, 2024"` and will need updating.
Add a case pinning a non-English locale to prove the formatter follows it — which is the whole
point and is currently untestable.

---

# 3b — Information architecture and cleanup

## 7. N6 — Two independent copies of "which screen am I on"

**Severity:** Fix · **Files:** `App.kt`, `PhoneRoot.kt`

`App` holds `currentScreen` for the expanded layout; `PhoneRoot` holds a second, unrelated one
for compact. Crossing the 600dp boundary by resizing, or changing the Layout setting, swaps
which composable is mounted and drops you back on Dashboard, because the other copy was never
told where you were.

The Layout setting makes this easy to hit: it lives in Settings, so switching to Compact
navigates you away from the screen you were configuring.

**Fix:** hoist `currentScreen` to `App` and pass it plus a setter into `PhoneRoot`. Cheap now
that round 1 already threaded `windowSize` through both roots.

**Tests:** assert that changing window size preserves the selected screen — a UI test driving
`App` at two widths.

---

## 8. N7 — The Library tab locks during a run in one layout and not the other

**Severity:** Fix · **Files:** `Sidebar.kt`, `PhoneRoot.kt`

`AppSidebar` passes `enabled = !isRunning` to the Library item. `PhoneRoot`'s bottom bar passes
no such thing. Same app, two rules about whether you may look at your library mid-run — and
neither explains itself: the disabled sidebar item renders at 30% alpha with no tooltip, no
helper text, no cursor change.

**Fix:** decide which behaviour is right and apply it to both. Recommendation: **drop the
lock**. The Library scans the output folder read-only, and round 1 moved that scan onto
`ioDispatcher`, so there is no correctness reason to block it. If the lock stays instead, say
why on hover — a control that refuses without explaining reads as a bug.

**Tests:** assert both navigations expose the same enabled state for a given `isRunning`.

---

## 9. N9 — Library's empty state has no control when a folder is already set

**Severity:** Fix · **Files:** `LibraryScreen.kt`

The "Select Download Folder" button is guarded by `if (downloadFolder == null)`, so the empty
state only offers a way out when nothing has been chosen yet. Set a folder that turns out to be
empty or wrong and you get an icon, the sentence *"No memories found. Start a download from the
Dashboard to populate your library."*, and no control of any kind — you cannot see which folder
is being scanned, change it, or refresh it, because the refresh affordance is behind the same
condition.

**Fix:** always show the folder path and a way to change it. Distinguish the three real cases,
which currently share one message:

1. No folder chosen → picker.
2. Folder chosen, nothing found → show the path, offer change and refresh.
3. Filters excluding everything → say so, offer to clear filters. This one currently shows
   "no memories" even with a full library.

**Tests:** assert the distinct message and available controls in each of the three states.
Case 3 is a genuine bug, not just missing polish.

---

## 10. N10 — Settings has a different icon depending on the layout

**Severity:** Cleanup · **Files:** `Sidebar.kt`, `PhoneRoot.kt`

`Icons.Filled.Tune` (sliders) in the sidebar, `Icons.Filled.Settings` (gear) in the bottom bar.
Dashboard and Library match across both; only Settings diverges.

**Tests:** folded into item 2's shared-navigation test.

---

## 11. D2 — Full-size previews are upscaled thumbnails

**Severity:** Fix · **Files:** `LibraryScreen.kt`

`loadFullImage` is implemented on all four targets and never called. `MediaPreviewDialog`
instead loads a cached JPEG scaled to `320:-1` and draws it with `ContentScale.Fit` into a
surface up to 860 × 680 — so every photo preview is a blurry 2.7× upscale of a re-compressed
thumbnail.

**Fix:** progressive load. Show the thumbnail first (instant, already in the LRU that round 1
wired up), then swap in `loadFullImage` when it resolves, so nothing gets slower. Run it on
`ioDispatcher`, matching the thumbnail path.

**Tests:** assert the dialog requests the full image for a photo and does not for a video
(where `VideoPlayer` handles it) — injectable loader rather than real files.

---

## 12. D3 — Delete the duration UI

**Severity:** Cleanup · **Files:** `LibraryScreen.kt`, `MediaScanner.kt` ×2

`LibraryItem.duration` is assigned a literal `null` in both real scanners, so the MediaCard
duration badge and the DURATION inspector row can never render.

**Decided: delete rather than populate.** Remove the field, the badge and the inspector row.
Rendering nothing is worse than either option, and the ffprobe-per-file cost is not worth it
for a badge. If it comes back later, the value belongs in `vault_index.json` alongside
`hasGps`/`hasOverlay`, written once at import — not probed at scan time.

**Tests:** none (deletion). Existing `MediaScannerTest` cases that construct `LibraryItem` need
updating.

---

## 13. D5 — Two unused public composables

**Severity:** Cleanup · **Files:** `DashboardScreen.kt`, `SettingsScreen.kt`

`TerminalDot` (a coloured status dot from an earlier terminal-chrome design) and `SettingsRow`
(icon + title + description) are both `public` and called from nowhere.

**Fix:** delete `TerminalDot`. For `SettingsRow`, the interesting case — Settings hand-rolls
that exact layout four times inline. Adopt it in those four places instead of deleting it.

Round 2's detekt would have caught both; this is the backlog it inherits.

**Tests:** none (refactor). The existing suite must pass untouched — if it does not, the
`SettingsRow` adoption changed behaviour and is not a refactor.

---

# 3c — The three feature builds

## 14. N11 — Sort control

**Severity:** Fix · **Files:** `LibraryScreen.kt`, `MediaScanner.kt`, `strings.xml`

The comment reads `// Filter + search bar + sorting controls`. There are none — the row holds
three type tabs and a search field, and the grid is hardwired newest-first by `scanMediaFiles`.
The two resources for it are orphaned. There is also a stray empty `Row` wrapper around the tab
group.

**Fix (decided: build):** add Newest / Oldest / Largest / Name. All four are already derivable
from `LibraryItem` — no new scanning. Sort in the composable rather than in `scanMediaFiles`,
so changing it does not re-scan the folder; `scanMediaFiles` keeps its current order as the
default, which preserves the round-1 capture-date sort logic and its tests.

Use an enum, matching item 5. Revives `lib_sort_newest` and `lib_filter_date`.

**Tests:** assert each sort order produces the expected sequence, including the tie-breaking
that `MediaScannerTest` already pins for the default — the existing capture-date-vs-mtime
tests must keep passing untouched.

---

## 15. N12 — Reveal in file manager

**Severity:** Fix · **Files:** `LibraryScreen.kt`, `Platform.kt` ×4

For a tool whose entire job is producing a folder of media, nothing in the Library opens that
folder or reveals a file — even though round 1 added `openUrl` and `VideoPlayer` already
contains a working `Desktop.getDesktop().open()` helper.

Separately, Library's `onOpenFolder` is bound to `pickOutputFolder`, so the button labelled
"Select Download Folder" changes **where the next pipeline run writes**, not just what the
Library displays. That is a real trap and should be relabelled regardless.

**Fix (decided: build):** add "Reveal in file manager" to the inspector and "Open output
folder" to the header, via a new `expect fun revealInFileManager(path: String)` alongside
`openUrl`. Desktop gets `Desktop.open` on the parent directory (with `xdg-open` /
`open -R` / `explorer /select,` fallbacks); Android and iOS get no-ops, since neither has a
user-facing file manager to reveal into.

Relabel the picker to say it sets the pipeline's output path.

**Tests:** the platform call itself is not unit-testable, but assert the actions are present
in the inspector and header for desktop and absent where `revealInFileManager` is a no-op.

---

## 16. D4 — Favourites

**Severity:** Fix · **Files:** `LibraryScreen.kt`, `MediaScanner.kt`, `model/FileMeta.kt`

`LibraryItem.favorited` has a `= false` default, is never assigned, and gates a heart badge on
`MediaCard` that has therefore never rendered. Unlike duration there is no data for it in the
export — this is a SnapVault-side feature needing its own persistence.

**Fix (decided: build).** The largest item in the round, and the only one adding persisted
state:

1. Add `favorited: Boolean = false` to `FileMeta`, which already persists to
   `vault_index.json` and already carries `hasGps` / `hasOverlay`.

   **The default is not optional.** `FileMeta` is currently
   `data class FileMeta(val hasGps: Boolean, val hasOverlay: Boolean)` — no defaults on
   either field. `kotlinx.serialization` requires a value for every parameter without one, so
   a new field added without a default makes every **existing** `vault_index.json` fail to
   parse. That index is read by `scanMediaFiles` inside a `runCatching { … }.getOrDefault(emptyMap())`,
   so the failure would not surface as an error — the Library would silently show every item
   as having no GPS and no overlay. Add the default, and add a test that an index written
   before this change still deserialises.
2. Read it in `scanMediaFiles` (both platforms) the same way those two are read.
3. Add a toggle to the inspector and the existing badge lights up.
4. Add a Favourites filter alongside item 5's enum.

The write path is the part to be careful about: `vault_index.json` is written by the pipeline,
so a favourite toggled during a run must not be clobbered by the run's own index write, and a
reset-index must not silently discard favourites. Decide explicitly whether reset clears them —
recommendation: it should not, and the reset copy should say so.

**Tests:** the highest-risk item here, so the most test coverage:

- A `FileMeta` written **before** this change still deserialises — the silent-data-loss case
  above.
- A favourite survives a round-trip through `vault_index.json`.
- A favourite survives a pipeline run that rewrites the index. This is the one that will
  actually break.
- The Favourites filter selects correctly.
- Reset-index behaves as decided, and the copy matches the behaviour.

---

## Sequencing

3a in order (1 → 6), since item 4's deletions depend on items 3 and 14 landing first — so
either do item 14 early or item 4 last. 3b is independent apart from item 10 folding into
item 2's test. 3c last: item 14 pairs with item 5's enum, and item 16 is the riskiest thing in
the round and wants the most settled base under it.

Run `./gradlew :composeApp:desktopTest` plus all three compile targets after each item.

## After round 3

The 38-finding audit is then closed. Remaining known items outside it, recorded in
`CLAUDE.md`: the `iosMain` `VideoPlayer` recomposition bug, and the `IS_DEBUG` task-name
inference that still produces different builds for `run` and `test`.
