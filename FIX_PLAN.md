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

| Finding | Claude | Codex | Gemini | Status |
|---|---|---|---|---|
| Extraction race / silent overwrite on duplicate filenames | BUG-02 | P0 | — | **Fixed** (uncommitted) |
| Stepper never reaches "complete" (`currentStep` stuck at 3) | BUG-05 | P1 | ✓ | **Fixed** (uncommitted) |
| Combine phase overwrites precise time with midnight | **BUG-01** | not run against real data | — | **Open — highest priority** |
| Unqualified `[SUCCESS]` despite partial failures | BUG-15 | P1 | — | Open |
| Late-phase progress stuck at 0% / stale speed-ETA | BUG-04 | P1 | ✓ | Open |
| Inputs stay editable mid-run | BUG-16 | P1 | — | Open |
| Dedupe reports deletes that failed | BUG-17 | P1 | — | Open |
| 3,617 metadata failures, zero explanation | BUG-12 | (would surface under P1 above) | — | Open |
| Stop/Start race re-enables Start mid-run | BUG-06 | (related to P1 lock finding) | — | Open |
| CI gap: pushes to `develop` unguarded; Android job has no SDK | BUG-08 | P2 (stale docs) | — | Open |
| Library can't see all formats the pipeline emits | BUG-18 | P2 | — | Open |
| exiftool pipes not drained (deadlock class, unhit in practice) | BUG-10 | — | — | Open |
| Dedupe ignores Stop (impact corrected: ~3s on real vault) | BUG-07 | — | — | Open |
| `memories.html` triggers a false-anomaly warning per zip | BUG-13 | — | — | Open |
| Combine log lines print out of order | BUG-14 | — | — | Open |
| Stale progress ring on failed/cancelled run (partially fixed) | BUG-09 | — | — | Open (partial) |
| Linux source checkout has no bundled ExifTool | BUG-11 | — | — | Open (docs gap) |
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

## Pass 4 — Lifecycle / concurrency

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

---

## Pass 5 — Process / polish

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

1. `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :composeApp:desktopTest` → 66/66 (already
   green; keep green through every pass above).
2. Re-run the real export (or an equivalent large synthetic set with overlays) and confirm:
   - 0% of combined files carry a midnight timestamp (BUG-01 gate).
   - No unexplained metadata failures in the log (BUG-12 gate).
   - Progress ring has no multi-second stall at 0% (BUG-04 gate).
   - Final state reads "Completed with warnings" if — and only if — failures were induced
     (BUG-15 gate).
3. Manual smoke test per Codex's list: normal ZIP, overlapping/duplicate-filename ZIPs, zero-
   overlay import, large overlay batch, cancellation mid-run, metadata-tool unavailable, dedupe
   delete failure (e.g. read-only target file).
4. `./gradlew packageReleaseDistributionForCurrentOS` to a clean completion (Codex flagged this as
   inconclusive last run — needs a full re-run, not just compilation).
