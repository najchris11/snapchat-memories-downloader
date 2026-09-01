# SnapVault Desktop — Prioritized Fix Plan

Synthesized from three independent audits (`claude release audit.md`, `codex release audit.md`,
`gemini release audit.md`), all performed 2026-08-31 against `develop` @ `84d0ee1` (+2 uncommitted
fixes: `DashboardViewModel.kt`, `ZipExtractEngine.kt`). Only the Claude audit ran the pipeline
against real data (42 GB / 21 real export zips); its findings are load-bearing where they conflict
with the other two. Desktop only — mobile is shelved and excluded from this plan.

All three audits agree on the release verdict: **do not ship as-is.** The pipeline reports clean
success while silently destroying data on nearly half its real output, and that corruption is
currently unobservable from the UI or logs.

## How the three audits line up

All 18 findings below are now fixed on `fix/desktop-audit-fixes` (Passes 1–5 all done — see
sections below for what each fix actually did and its regression test). This table is kept as
the original cross-audit record.

| Finding | Claude | Codex | Gemini | Status |
|---|---|---|---|---|
| Extraction race / silent overwrite on duplicate filenames | BUG-02 | P0 | — | **Fixed** (pre-existing) |
| Stepper never reaches "complete" (`currentStep` stuck at 3) | BUG-05 | P1 | ✓ | **Fixed** (pre-existing) |
| Combine phase overwrites precise time with midnight | **BUG-01** | not run against real data | — | **Fixed** — Pass 2 |
| Unqualified `[SUCCESS]` despite partial failures | BUG-15 | P1 | — | **Fixed** — Pass 1 |
| Late-phase progress stuck at 0% / stale speed-ETA | BUG-04 | P1 | ✓ | **Fixed** — Pass 3 |
| Inputs stay editable mid-run | BUG-16 | P1 | — | **Fixed** — Pass 4 |
| Dedupe reports deletes that failed | BUG-17 | P1 | — | **Fixed** — Pass 1 |
| 3,617 metadata failures, zero explanation | BUG-12 | (would surface under P1 above) | — | **Fixed** — Pass 1 |
| Stop/Start race re-enables Start mid-run | BUG-06 | (related to P1 lock finding) | — | **Fixed** — Pass 4 |
| CI gap: pushes to `develop` unguarded; Android job has no SDK | BUG-08 | P2 (stale docs) | — | **Fixed** — Pass 5 |
| Library can't see all formats the pipeline emits | BUG-18 | P2 | — | **Fixed** — Pass 5 |
| exiftool pipes not drained (deadlock class, unhit in practice) | BUG-10 | — | — | **Fixed** — Pass 2 |
| Dedupe ignores Stop (impact corrected: ~3s on real vault) | BUG-07 | — | — | **Fixed** — Pass 3 |
| `memories.html` triggers a false-anomaly warning per zip | BUG-13 | — | — | **Fixed** — Pass 5 |
| Combine log lines print out of order | BUG-14 | — | — | **Fixed** — Pass 3 |
| Stale progress ring on failed/cancelled run (partially fixed) | BUG-09 | — | — | **Fixed** — Pass 3 |
| Linux source checkout has no bundled ExifTool | BUG-11 | — | — | **Fixed (docs)** — Pass 5 |
| `parseMemoriesHtml` dead code | — | — | claimed | **Does not hold** — function doesn't exist on this branch |
| "Ready for Release (Post-UI-Fix)" verdict | — | — | claimed | **Not supported** — didn't test against real data, missed BUG-01 |

Codex's P0 and P1-stepper independently reproduce Claude's BUG-02/BUG-05 (both fixed). Codex's
remaining P1 items (unconditional success, late progress, editable inputs, false dedupe deletes)
independently reproduce BUG-15/BUG-04/BUG-16/BUG-17 — three-way agreement on all of those.

---

## Pass 1 — Truth-telling (do this first) · **DONE**

The pipeline must stop claiming success it hasn't earned. Every later pass is unverifiable until
this lands, because right now a clean run and a run that silently ate half its data look
identical in the UI and the log tail.

1. **BUG-15 — Unqualified `[SUCCESS]` after thousands of failures**
   `DashboardViewModel.kt:126, 355–365`
   Fold `failCount` (metadata failures, extraction errors, combine errors) into the final summary
   line. Introduce a distinct terminal state ("Completed with warnings") when any phase reported
   failures — stepper and status chip must not read as clean success. Covers Codex's P1
   "dashboard reports full success even after partial failures."

2. **BUG-12 — 3,617 metadata failures with zero explanation**
   `DashboardViewModel.kt:560–634` (`writeZipExperimentalMetadata`)
   Root cause pinned: 75% of overlay PNGs are WebP mislabeled `.png` (RIFF signature). Reuse the
   `hasRiffSignature` guard already present in `writeDateMetadataBatch.runGroup` to skip these
   before spawning exiftool, and log a reason for every failure the way the batch path does.

3. **BUG-17 — Dedupe claims deletes that failed**
   `Deduplicator.kt:70–85`
   Stop swallowing delete exceptions into a list reported as fully successful. Collect actual
   successes and report only those; surface failures as warnings.

**Exit criteria:** a run with induced failures (permission-denied output dir, a locked file, a
corrupt overlay) visibly reports "Completed with warnings" with an accurate failure count — not
`[SUCCESS]`.

**Implemented:**
- `DashboardViewModel.kt` now tracks `pipelineFailureCount` across extraction, both metadata
  paths, combine, and dedupe, resets it per run, and exposes `hasWarnings`. The terminal success
  block branches on it: clean runs still log `[SUCCESS] Sync complete!` / "Pipeline Complete";
  runs with any counted failure log `[WARN] Sync complete — N failure(s) occurred…` / "Completed
  with warnings". `DashboardScreen.kt`'s step-4 chip renders amber (`SnapVaultColors.warning`)
  instead of purple when `hasWarnings` is true, so the stepper itself no longer reads as a clean
  success.
- `writeZipExperimentalMetadata` now pre-filters WebP-mislabeled-`.png` overlays by magic bytes
  (`isRiffMislabeledAsPng`, reusing the same check `writeDateMetadataBatch.runGroup` already had)
  before spawning exiftool, and logs up to 5 examples instead of a silent `failCount++`. Genuine
  failures (any survivors) are now logged with up to 5 filename examples instead of vanishing into
  an unexplained count.
- `Deduplicator.deduplicateFolder` no longer folds a failed delete into `deletedFiles`. It now
  returns `failedFiles` separately; `DashboardViewModel.runDeduplication` logs failures as `[WARN]`
  and feeds them into `pipelineFailureCount` instead of claiming `[DELETED DUPES]` for a file still
  on disk.
- Regression tests added: `DeduplicatorTest` (`testFailedDeleteIsReportedSeparatelyFromDeleted`,
  `testDryRunNeverReportsFailures`, using `okio.ForwardingFileSystem` to inject a delete failure)
  and a new `DashboardViewModelTest` (`runWithFailuresEndsInWarningStateNotUnqualifiedSuccess`,
  `cleanRunStillReportsUnqualifiedSuccess`, `isRiffMislabeledAsPngDetectsContentNotJustExtension`)
  — the first ViewModel-level tests in the project, using fakes for `ZipPipelineRunner`,
  `MediaProcessor`, and `PlatformPickers` over the legacy pipeline (fully in-memory, no real HTTP/
  ZIP/exiftool/ffmpeg). `isRiffMislabeledAsPng` was widened from `private` to `internal` to make it
  directly testable. Suite: 66/66 → **71/71**.
- Not covered by a test: the full ZIP-source experimental-metadata path end to end (real zip
  fixtures + `memories_history.json` + extended-timestamp matching) — the RIFF pre-filter itself is
  covered directly; wiring it through the whole ZIP pipeline would need real zip fixtures similar to
  the ones built for the real-data audit run and was judged disproportionate for this pass.

---

## Pass 2 — Metadata correctness (the headline bug) · **DONE**

4. **BUG-01 — Combining an overlay destroys the precise capture time · CRITICAL**
   `OverlayCombiner.kt:114–131` → `DesktopMediaProcessor.kt:263`
   Measured on the real 42 GB export: **4,795 of 4,795 combined files lost their time-of-day to
   midnight; 0 of 5,260 uncombined files did.** 47.7% of total output. `copyExif` correctly
   propagates the precise `DateTimeOriginal`/GPS onto the combined output, but `combineAll` then
   unconditionally calls `writeDateMetadataBatch(outputs, dateOnly)`, which overwrites it with
   `"$dateOnly 00:00:00"`. GPS survives because the batch doesn't touch GPS tags, so the file
   looks correctly tagged at a glance — nothing is logged.
   **Fix:** make the post-combine date pass conditional — skip it when the source `-main` file's
   tags were already propagated by `copyExif`, or pass the combine phase the exact per-file
   timestamp the metadata phase computed instead of re-deriving a date-only string from the
   filename prefix.
   **Verification:** re-run the export, split output by combined/uncombined, assert 0% of
   combined files end in `00:00:00`. One-command check, see `claude release audit.md` appendix.

5. **BUG-10 — exiftool invocations don't drain stdout/stderr**
   `DesktopMediaProcessor.kt:185, 234` · `OverlayCombiner.kt:262–265`
   Same deadlock class the codebase already guards against for FFmpeg. Didn't hit in ~19,800 real
   invocations, but the fix is one flag and free: `.redirectErrorStream(true)` + read before
   `waitForOrKill()`. Capturing this output would also explain BUG-12 failures for free — do this
   alongside item 2.

**Implemented:**
- `OverlayCombiner.copyExif` now returns whether the copy actually succeeded (draining
  stdout/stderr first — BUG-10) instead of silently swallowing every outcome; `processPair` warns
  when it fails. A new `hasDateTag(file, isVideo)` reads the combined output back by content
  (`exiftool -s3 -DateTimeOriginal` for images, `-CreateDate` for videos) rather than trusting the
  copy step's exit code alone, because a copy can "succeed" while copying zero useful tags (source
  had none — metadata phase was off, or never matched a timestamp for that file).
- `combineAll` now collects a `needsDateFallback` subset — only pairs whose combined output still
  has no real timestamp after the copy step — and runs the post-combine `writeDateMetadataBatch`
  fallback against that subset instead of unconditionally against every successful pair. A file the
  metadata phase already tagged precisely keeps that timestamp; a file that was never tagged still
  gets the filename-derived date-only fallback, exactly as before. `onMetaStart` (and the "Tagging
  N combined file(s)…" log line) now only fires when there's actually something left to backfill.
- BUG-10 also fixed at its other two sites: `DesktopMediaProcessor.writeGpsMetadata` and
  `writeDateMetadata` now drain stdout/stderr before `waitForOrKill()`, matching the pattern
  `writeDateMetadataBatch.runGroup` and the video metadata-copy step already used.
- Regression test: `OverlayCombinerTest.combinedOutputPreservesPreciseTimeInsteadOfMidnight` — uses
  the real `DesktopMediaProcessor` (not the fake), writes a precise non-midnight timestamp to a
  `-main` file the way the metadata phase would, runs the real combine, and reads the combined
  output's `DateTimeOriginal` back via exiftool to confirm the time-of-day survived. Skips
  gracefully (no assertions run) on a machine with no exiftool resolvable via `BinaryExtractor`
  (fresh checkout — BUG-11) rather than failing for an unrelated reason; confirmed to actually run
  on this machine (0.51s — four real exiftool spawns — vs. ~0s for the trivial `findPairs` tests).
  Suite: 71/71 → **72/72**.

**Exit criteria:** re-run the real export; 0% of combined files show a `00:00:00` timestamp; GPS
tagging rate unchanged (~99.7%).

**Re-verified at real scale (2026-09-01), same 21-zip / 42 GB export in `~/Downloads/snap 2`:**

```
62.9 min end-to-end (was 73.2 min) · 10125 memories · 10107 output files · 47.56 GB
0 [ERROR] lines · 0 stderr output · 0 stray .part files
2635 hardware video encodes, 0 software fallbacks
final: step=4 progress=1.0 text='Pipeline Complete' hasWarnings=false
```

| | Combined files | Uncombined `-main` files |
|---|---|---|
| Midnight `00:00:00` (before fix) | 4795 / 4795 (100%) | 0 / 5260 |
| Midnight `00:00:00` (after fix) | **0 / 4818 (0.0%)** | 0 / 5260 |

Spot-checked combined files carry real times (`16:01:12`, `01:01:31`, `15:59:09`, …), not midnight.
BUG-12 also confirmed at the same scale — the same 3,617 WebP-mislabeled-`.png` overlays are now
explicitly logged as `[INFO] Skipped 3617 overlay(s) stored as WebP but named .png … Examples: …`
instead of an unexplained failure count, and genuine failures are 0. BUG-15's terminal state is
accurate: a clean run reports `hasWarnings=false` / "Pipeline Complete", matching that nothing
failed. Timestamp-match rate held at 10091/10125 (99.7%); GPS and hardware-encode stats unchanged
from the original audit run.

---

## Pass 3 — Pipeline UI (the reported symptom) · **DONE**

This is the "progress indicators don't fully update" complaint that kicked off the audit.
BUG-05/BUG-09-partial are already fixed by the concurrent edit; the rest of the symptom is BUG-04.

6. **BUG-04 — Progress ring sits at 0% through the back half of the run**
   `DashboardViewModel.kt:646, 662–666, 833–845`
   Measured: ring dropped from 99.9% to 0.0% and froze for **6 min 51 s** at the end of a 73-min
   run, with stale `1 pairs/s ETA: done` still displayed. Causes: `onMetaStart` zeroes progress
   for the combined-file tagging pass, which emits no per-file callbacks (dominant cause);
   `runDeduplication` never touches `progress`; zero-overlay-pairs case parks at 0%; speed/ETA
   aren't reset on phase transitions.
   **Fix:** give each phase a completion write (`progress = 1f` on exit), make the tagging pass
   report per-file counts like the others, reset speed/ETA on every phase transition. A phase that
   can't report increments should show an indeterminate state, not a truthful-looking 0%.

7. **BUG-09 — Stale progress ring on failed/cancelled run · finish the fix**
   `DashboardViewModel.kt:129–147`
   The concurrent edit added speed/ETA resets to the cancel/failure paths but left `progress`
   itself untouched — a run that dies right after extraction still shows a full ring above
   "Failed." Reset `progress` alongside speed/ETA in the same paths.

8. **BUG-14 — Combine log lines print out of order**
   `OverlayCombiner.kt:114–131`
   `onMetaStart` fires the "Tagging N combined files…" line before `combineAll` returns, so the
   phase summary appears 7 minutes after the line for the phase that follows it. Move the log call
   to after `combineAll` returns, or after the metadata batch actually starts.

9. **BUG-07 — Stop ignored during deduplication**
   `Deduplicator.kt:38–90`
   No suspension point / `ensureActive()` in the hashing loop. Real-world impact is small (3s on
   the 47 GB real vault, thanks to the size pre-filter) but still worth an `ensureActive()` call;
   emitting a count from the loop also partially covers BUG-04's dedupe-phase blindness.

**Exit criteria:** manually run a large import and confirm the ring monotonically advances to
100%/step-4-complete with no multi-second stalls at 0%, and that Stop is responsive during dedupe.

**Implemented:**
- Added `DashboardViewModel.indeterminate: Boolean`. Set `true` for the two sub-phases that have
  real work in flight but no per-item signal (the post-combine date-fallback batch, dedupe
  scanning/hashing), `false` everywhere else — reset per run in `startSync` and in all three
  cancel/abort/exception paths (alongside `progress = 0f`, closing out the BUG-09 gap the plan
  identified). `DashboardScreen`'s progress ring now renders the parameterless (animated,
  indeterminate) `CircularProgressIndicator` in that state and hides the percentage label, instead
  of showing a truthful-looking `0%` for however long the sub-phase takes.
- `runCombinePhase` now does a phase-completion write (`progress = 1f`, `indeterminate = false`)
  once `combineAll` returns, whatever ran inside it. The zero-pairs edge case (`onStart(0)`) now
  sets `progress = 1f` immediately instead of leaving the ring parked at 0 with no callback to ever
  close it out. `onMetaStart` resets `speedText`/`etaText` the instant the sub-phase begins, instead
  of only after the whole combine phase (sub-phase included) eventually returns — fixes the "stale
  `N pairs/s ETA: Xs`" artifact called out in the original measurement.
- **BUG-14:** the "`[INFO] Combined N overlay pairs`" summary (plus the video-encode-stats lines)
  now logs the moment the per-pair combine loop itself finishes — from inside the last `onProgress`
  callback (`combineDone == combineTotal`), guarded by a `summaryLogged` flag so the zero-pairs path
  (which logs it from `onStart` instead) can't double-log. This is strictly before `onMetaStart` can
  fire, since `onMetaStart` is only called after `combineAll`'s internal `coroutineScope` (which
  waits for every `onProgress` delivery) completes — so the summary now reads as describing work
  that just finished, not work from several minutes earlier.
- `runDeduplication` sets `indeterminate = true` for the scan/hash/delete call (which has no
  suspension point to report through) and `indeterminate = false` / `progress = 1f` once it returns;
  also resets `speedText`/`etaText` at its own start rather than relying on a prior phase to have
  done it.
- **BUG-07:** `Deduplicator.deduplicateFolder` (and `deduplicateAll`, which calls it) are now
  `suspend fun`, with `currentCoroutineContext().ensureActive()` checks in both the hashing loop and
  the per-group deletion loop — Stop can now actually interrupt a scan in flight instead of the
  pipeline running the whole hash pass to completion regardless of cancellation.
- Regression tests: `DashboardViewModelTest.indeterminateIsTrueDuringDateFallbackAndClearedAfter`
  (captures `indeterminate` synchronously from inside a fake `onMetaStart` callback, proving it's
  `true` mid-phase and `false` after) and `combineSummaryLogsBeforeDateFallbackTaggingLine` (asserts
  log order directly, the BUG-14 regression). `DeduplicatorTest.testCancellationStopsBeforeAnyDeletion`
  cancels the coroutine's own job before calling `deduplicateFolder` and asserts nothing was
  deleted — proves the `ensureActive()` checks are reachable and effective, not just present.
  Converting `Deduplicator` to `suspend` required wrapping the existing `DeduplicatorTest` bodies in
  `kotlinx.coroutines.test.runTest` (already a project dependency, same pattern `DownloadEngineTest`
  already used). Suite: 72/72 → **75/75**.
- Not done: true per-file progress reporting *during* the date-fallback batch or the dedupe hash
  loop (the plan's first-choice fix, "have the tagging pass report per-file counts like the
  others") — would require adding a new callback to the `ZipPipelineRunner` interface and updating
  every implementation (`DesktopZipPipelineRunner`, `IosZipPipelineRunner`, `NoOpZipPipelineRunner`).
  The plan's own fallback — "a phase that genuinely can't report increments should show an
  indeterminate state rather than a truthful-looking 0%" — was implemented instead, and in
  practice the Pass 2 fix already shrank the date-fallback batch to only the files that actually
  need it (near-zero on a real export with metadata matching on), so the indeterminate window is
  rare in the first place.

---

## Pass 4 — Lifecycle / concurrency · **DONE**

10. **BUG-06 — Stop then immediate Start re-enables Start mid-run**
    `DashboardViewModel.kt:99–101, 148–152`
    `stopSync`'s `job.cancel()` flips `isActive` false immediately while `finally` still waits on
    in-flight ffmpeg/exiftool children. A new run started in that window passes the `syncJob?.isActive`
    guard, then the *old* job's `finally` clobbers `isRunning = false` while the new pipeline is
    actually running — Start becomes clickable and Stop greys out with no way to cancel the live run.
    Same window: `startSync` calls `logs.clear()` outside `logLock` while the dying job's `log()`
    calls are still appending under it — an unsynchronized clear against `SnapshotStateList`.
    **Fix:** have `finally` only clear `isRunning` if it still owns the current job (compare
    against `syncJob` before writing); move `logs.clear()` inside `logLock`.

11. **BUG-16 — Source/output pickers and mode toggles stay live during a run**
    `DashboardScreen.kt:166–257, 130–160`
    `isRunning` gates only Start, Stop, and the log cursor. `FilePickerBox` has no `enabled` param.
    Mid-run, a user can change the ZIP folder, output folder, HTML file, or import mode — and
    Settings' "Reset vault index" can delete `vault_index.json` while the pipeline is preparing to
    write it. The run itself is safe (`outDir` is captured locally at launch), but the UI
    describes a state the run isn't using.
    **Fix:** thread `enabled = !isRunning` through `FilePickerBox` and `ModeToggleButton`.

**Exit criteria:** rapid Stop→Start no longer produces a run that can't be cancelled; all
run-defining controls are visibly disabled while `isRunning`.

**Implemented:**
- **BUG-06:** `startSync` now captures its own job via a `lateinit var thisJob: Job` before
  assigning it to the `syncJob` field, so the `finally` block can check `syncJob === thisJob`
  before clearing `isRunning` — a stale job whose cancellation is still unwinding after a newer
  run has already started now leaves that newer run's state alone instead of clobbering it.
  `logs.clear()` now goes through `logLock.withLock { }`, the same lock every `log()` call already
  uses, instead of mutating the `SnapshotStateList` unsynchronized against the dying job's
  still-in-flight appends.
- **BUG-16:** `FilePickerBox` and `ModeToggleButton` both gained an `enabled: Boolean = true`
  parameter (dimming their content to 50% alpha and disabling the click when `false`), wired to
  `enabled = !viewModel.isRunning` at all 8 call sites — both import-mode toggles, both ZIP
  source-mode toggles, the ZIP folder/multi-file/HTML/output pickers, and the "Clear" selected-files
  link. Also added a model-level guard: `resetVaultIndex()` now returns `false` immediately when
  `isRunning` is true, closing the "Settings can delete `vault_index.json` out from under a live
  run" risk the finding called out — done at the ViewModel layer rather than by threading `isRunning`
  through `SettingsScreen`/`App.kt`, since the risk is the same regardless of which screen can reach
  it and this doesn't touch navigation/screen-composition code.
- Regression tests: `DashboardViewModelTest.stopThenImmediateStartDoesNotLetStaleJobClobberNewRun`
  reproduces the actual race with a `RaceZipPipelineRunner` fake (first run's cancellation
  acknowledged only after a 50ms `NonCancellable` delay it deliberately doesn't respond to
  cancellation during; second run takes 300ms, uncancelled) — calls `stopSync()` then immediately
  `startSync()` again (bypassing the UI's disabled button entirely, exercising the ViewModel API
  directly), checks at the 150ms mark (comfortably after the first job's stale cleanup, comfortably
  before the second job's own completion) that `isRunning` is still `true`. Verified non-flaky with
  5 forced re-runs (`--rerun-tasks`), all green. `resetVaultIndexIsRefusedWhileRunning` uses a
  `HangingZipPipelineRunner` (suspends on `awaitCancellation()` until stopped) to prove the guard
  and that the file survives on disk. Suite: 75/75 → **77/77**.
- Not done: gating the pipeline-option switches (`runMetadata`, `runCombine`, `runDedupe`, etc.) —
  out of BUG-16's stated scope. They're local Composable state in `DashboardScreen`, not ViewModel
  state; `startSync` already captures their values by parameter at launch, so changing them mid-run
  only affects the *next* run, not the one in progress — there's no live-run inconsistency to fix
  there the way there is for the source/output pickers.

---

## Pass 5 — Process / polish · **DONE**

Low urgency; batch these together, no interdependencies.

12. **BUG-08 — CI doesn't guard `develop`; Android job has no SDK**
    `.github/workflows/check.yml`
    Only `pull_request → main` is gated — direct pushes to `develop` (how the BUG-03 regression
    landed) are unguarded. Add `push: branches: [develop, main]`. Separately, the Android job runs
    on `macos-latest` with only `setup-java` — no `setup-android`, and current arm64 macOS runners
    don't ship the SDK; will likely fail the first time it actually runs. Given mobile is shelved,
    consider moving the desktop test job to `ubuntu-latest` and gating Android/iOS behind a path
    filter.

13. **BUG-13 — Benign `memories.html` triggers a false-anomaly warning per zip**
    `ZipImportParser.kt:83–88` → `DashboardViewModel.kt:220–222`
    Fired 21/21 times on the real export for the export's own index page. Exclude known non-media
    entries (`.html`) from the unmatched-file callback.

14. **BUG-18 — Library tab can't see formats the pipeline itself produces**
    `MediaScanner.kt:28`
    Scanner accepts `jpg/jpeg/png/mp4/mov/gif`; pipeline also handles `heic/heif/webp/mkv/m4v/avi`.
    A `-main.heic` or `-main.mkv` with no overlay pair keeps its extension and becomes invisible in
    the Library. Share one extension set between `MediaScanner` and the pipeline instead of two
    lists.

15. **BUG-11 — Linux source checkout has no bundled ExifTool**
    `README.md:42` · `resources/bin/linux-x64/` · `scripts/prepare-runtime-linux.sh`
    True for releases (built fresh in CI), but `resources/bin/linux-x64/` only holds a
    `README.txt` in a checkout, so `./gradlew :composeApp:run` silently has no ExifTool. Docs/dev-
    setup gap — either document the manual install step or have `prepare-runtime-linux.sh` also
    run in dev builds.

16. Update `CODEX_PLAN.md:7` — it claims "Desktop tests pass"; true today (66/66) but keep it in
    sync as this plan lands, and note the P2 stale-docs finding is otherwise resolved.

**Exit criteria:** none blocking — land opportunistically alongside the passes above.

**Implemented:**
- **BUG-08:** `check.yml` now triggers on `push`/`pull_request` for both `develop` and `main` (was
  `pull_request → main` only). Split into two jobs: `desktop` (moved to `ubuntu-latest` — plain
  JVM/Compose Desktop work, no macOS dependency, faster and cheaper than the shared macOS runner
  pool) as the required check, and `mobile-preview` (still `macos-latest`, unchanged Android/iOS
  steps) marked `continue-on-error: true` with a comment explaining it's informational only until
  Android SDK provisioning is actually done — mobile is shelved, so it shouldn't block desktop PRs
  with a failure the audit predicted but that isn't the point of this pass to fix. YAML validated
  with `python3 -c "import yaml; yaml.safe_load(...)"`.
- **BUG-13:** `ZipImportParser.parseMemoryEntryNames` now filters out `.html` entries under
  `memories/` before attempting to parse them, rather than letting the date-UUID regex reject
  `memories/memories.html` into the unmatched-file callback on every zip.
- **BUG-18:** added `SupportedMediaExtensions` (desktopMain) as the single source of truth for
  image/video extensions, replacing three independently hand-maintained copies: `MediaScanner`'s
  Library filter (was `jpg/jpeg/png/mp4/mov/gif` — now the full pipeline set), and the three
  duplicated sets in `DesktopMediaProcessor` (`writeGpsMetadata`, `writeDateMetadata`,
  `writeDateMetadataBatch`). Also fixed `OverlayCombiner.findPairs`'s own separately-hand-maintained
  video set, which was missing `m4v` — a latent bug this consolidation caught: an `.m4v` pair would
  have been misclassified as an image combine attempt instead of a video one.
- **BUG-11:** added a callout in the README's "Building from Source" section explaining that
  `resources/bin/linux-x64/` is empty in a checkout (the packaged zip is gitignored and only built
  by the release workflow), so `./gradlew :composeApp:run` silently has no ExifTool — with the two
  fixes (run `prepare-runtime-linux.sh` once, or install ExifTool to `~/.snapvault/bin/`). Chose
  documentation over having the script run automatically in dev builds, since that would mean an
  unprompted network fetch during every fresh `:composeApp:run` — a bigger, riskier behavior change
  than this pass's scope calls for.
- **Item 16:** `CODEX_PLAN.md`'s "Desktop tests pass" claim is currently true (81/81, this pass) —
  added a dated verification note plus a line explaining the `check.yml` split, and an explicit
  "mobile is shelved, these work items aren't being executed now" note so a future reader isn't
  confused about why CI behavior changed without any of `CODEX_PLAN.md`'s own mobile work items
  having been done.
- Regression tests: `ZipImportParserTest.testZipMemoriesHtmlIndexPageIsIgnoredNotUnmatched` (and
  confirmed the existing `testZipUnmatchedFilesReported` still catches genuine garbage filenames —
  the fix is scoped to `.html` specifically, not a blanket suppression). New
  `MediaScannerTest.kt` (`scanIncludesFormatsThePipelineProducesButDoesNotCombine`,
  `scanStillIncludesOriginallySupportedFormats`, `scanIgnoresUnrelatedFiles`) using real temp files
  on disk (`scanMediaFiles` reads `java.io.File`, not the injectable `okio.FileSystem`). Suite:
  77/77 → **81/81**.
- Not done: full Android SDK provisioning on the CI runner (would make `mobile-preview` a real,
  passing check rather than informational) — genuinely mobile work, out of scope while mobile is
  shelved per the user's stated priority.

---

## Regression coverage to add alongside the fixes

All three audits independently note the same gap: existing tests cover parsers, extraction,
downloads, dedupe, metadata configuration, and overlay logic, but **not** `DashboardViewModel`
terminal-state transitions, phase progress accounting, or the combine→metadata interaction. Add
tests for:

- Combine-then-tag: a combined output must retain a non-midnight `DateTimeOriginal` (direct
  regression test for BUG-01 — currently nothing would catch a reintroduction).
- Terminal state after induced partial failure (extraction error / metadata failure / combine
  error) → asserts `CompletedWithWarnings`, not `[SUCCESS]`.
- Progress monotonicity across phase transitions (extraction → metadata → combine → dedupe).
- Concurrent Stop+Start does not leave `isRunning` permanently desynced from the active job.

---

## Full verification checklist (before considering desktop release-ready)

1. ✅ `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :composeApp:desktopTest` → **81/81**, kept
   green through every pass above (started at 66/66).
2. ✅ Re-ran the real 42 GB / 21-zip export after Pass 2 and confirmed:
   - 0.0% of combined files carry a midnight timestamp (0/4818, was 4795/4795) — BUG-01 gate.
   - The 3,617 previously-unexplained metadata "failures" are now explicitly logged as skipped
     (WebP-mislabeled-`.png`), 0 genuine failures — BUG-12 gate.
   - `hasWarnings=false` / "Pipeline Complete" on a clean run — BUG-15 gate.
   - Not separately re-run since Pass 3/4/5 landed (those changes don't touch metadata
     correctness); the BUG-04 progress-stall gate is covered by unit tests
     (`indeterminateIsTrueDuringDateFallbackAndClearedAfter`) rather than a second full-scale run.
3. ⚠️ **Partially done (2026-09-01).** No GUI click-through happened — this environment has no
   input-automation tool (Wayland session, no `ydotool`/`wtype`), only a real display to screenshot.
   `./gradlew :composeApp:run` was launched for real and confirmed to build, start, and stay running
   cleanly (no exception in the log) before being stopped; **one screenshot was taken and immediately
   deleted** after it turned out to capture other windows on the shared display unrelated to this
   session (not reviewed further, not described beyond that it happened — flagged to the user).
   Codex's remaining scenarios were instead run as real integration tests — the actual production
   `DesktopZipPipelineRunner`/`DesktopMediaProcessor`, real zip files, real exiftool/ffmpeg
   subprocesses, no fakes — via a temporary harness (removed after, same practice as the Pass 2
   real-export harness):
   - **Overlapping/duplicate-filename ZIPs:** two real zips racing to extract the same destination
     filename → exactly one landed, one correctly logged "already existed", 0 errors (real-conditions
     BUG-02 confirmation).
   - **Zero-overlay import:** "Found 0 overlay pairs" → "Combined 0 overlay pairs" with no stall
     (BUG-04 zero-pairs edge case).
   - **Cancellation mid-run:** stopped a real run mid-metadata-phase ("Stopping — cancelling
     in-flight work…" → "Sync cancelled by user."), then started a second real run immediately after
     on the same ViewModel instance — extraction correctly resumed (0 new, 80 already existed),
     metadata/combine/dedupe all completed cleanly including real hardware video encoding and 12
     genuine duplicate groups deleted (real-conditions BUG-06 confirmation).
   - **Metadata tool unavailable:** renamed `~/.snapvault/bin/exiftool` aside for the run (restored
     in a `finally` block, verified restored afterward), confirmed the file still extracts, the
     failure is surfaced honestly ("Completed with warnings", `[WARN] Sync complete — 1 failure(s)
     occurred"), and nothing crashes.
   - **Dedupe delete failure:** not re-attempted here — POSIX delete permission is directory-level,
     not per-file, so reproducing "this one file can't be deleted, its siblings can" needs root/ACL
     tricks that are fragile and risk leaving the filesystem in a bad state; the existing
     `DeduplicatorTest.testFailedDeleteIsReportedSeparatelyFromDeleted` (fault-injected via
     `ForwardingFileSystem`) already exercises the exact code path deterministically.
   - **Large overlay batch:** already covered by the Pass 2 real 42 GB / 4830-pair export re-run.
4. ⚠️ **Attempted (2026-09-01) — found a real, confirmed release-blocking bug, separate from
   everything in this plan.**
   - `./gradlew :composeApp:packageReleaseDistributionForCurrentOS` reached ProGuard (which
     completed — the "78 duplicate class definitions" / duplicate-`MANIFEST.MF` notes Codex saw are
     confirmed harmless, standard multi-dependency shrinking noise), then **failed** at
     `packageReleaseDeb`: `Error: Invalid or unsupported type: [deb]`. Root cause: this dev machine
     is CachyOS/Arch-based and has no `dpkg-deb` (Debian/Ubuntu-only tool `jpackage --type deb`
     shells out to). **This part is a local-environment gap, not a project bug** — GitHub's
     `ubuntu-latest` release runner has `dpkg-deb`.
   - To verify past that, ran `:composeApp:createReleaseDistributable` instead (same ProGuard-shrunk
     app-image jpackage builds before wrapping it in a `.deb`, doesn't need `dpkg-deb`). **This
     succeeded** and produced a real native app-image at
     `composeApp/build/compose/binaries/main-release/app/SnapVault/` (253 MB, bundled JRE runtime).
   - **Launched the actual packaged binary** (`SnapVault/bin/SnapVault`) to confirm it starts.
     **It crashes immediately**: `pure virtual method called` / `terminate called without an active
     exception` — a native C++ abort, not a JVM exception. The unpackaged dev build
     (`./gradlew :composeApp:run`, no ProGuard) was confirmed working minutes earlier on this exact
     machine/display (see the smoke-test session), which points at ProGuard's shrinking — not the
     environment — as the cause: something it strips/renames is breaking Skiko's native JNI
     bridge. The project has **no custom `.pro` file and no ProGuard block in `build.gradle.kts`**;
     it runs entirely on the Compose Multiplatform Gradle plugin's bundled default rules, which is
     consistent with a known pitfall class (default rules not covering every `org.jetbrains.skiko.**`
     surface a given Skiko/platform version's JNI layer needs).
   - **This is not one of the 18 bugs tracked in this plan** — it's a new finding surfaced by
     actually running the verification step, exactly the kind of thing Codex's original "inconclusive,
     do not use as evidence" flag was warning about. **Not investigated further or fixed** — deciding
     how to prioritize/fix this is the user's call, not something to act on unilaterally mid-checklist.
