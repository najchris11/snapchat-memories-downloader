# Desktop release remediation plan

Source: [desktop-release-audit-2026-09-15.md](desktop-release-audit-2026-09-15.md) (findings
D01–D22, plus the unlabeled restart/resize observations) and its reproduction probes in
[desktop-audit-probes.md](desktop-audit-probes.md). This plan turns that audit into ordered,
testable work. It does not re-argue the findings — read the audit for evidence and rationale.

**Every item below follows the repository's TDD policy in `CLAUDE.md`: write the failing test,
watch it fail for the right reason, then make it pass.** Nothing here is "add a test after."
If a task's test passes on the first run, the test is wrong or the bug is stale — stop and
find out which before moving on.

## How to work a batch

For every task:

1. **Locate.** Confirm the file/function named still matches current `develop`. The audit was
   written against `7b0cfa2`; re-grep before trusting a line reference.
2. **Red.** Write the test named in the task (or a better name if you find one) in the listed
   test file. Run it alone:
   `./gradlew :composeApp:desktopTest --tests "*ClassName"`. Confirm it fails, and read the
   failure message — it must fail on the assertion describing the bug, not on a compile error
   or an unrelated exception.
3. **Green.** Implement the minimal fix. Re-run the same test.
4. **Prove the test isn't vacuous.** Temporarily revert the production fix (`git stash`
   or comment it out), rerun the single test, confirm it fails again, then restore the fix.
   This step is mandatory for every regression test, not optional diligence — it's the
   difference between coverage and the appearance of coverage.
5. **Full suite.** `./gradlew :composeApp:desktopTest` before moving to the next task in the
   batch. Batches are ordered so later fixes assume earlier ones landed; don't skip ahead.
6. **Three-target check** for any `commonMain` change: `compileKotlinDesktop`,
   `compileDebugKotlinAndroid`, `compileKotlinIosSimulatorArm64`.
7. Commit each task separately (small, tested, revertible). Do not bundle unrelated findings
   into one commit — several of these touch the same files (`DashboardViewModel.kt`,
   `ZipExtractEngine.kt`) and reviewers need to see which change fixed which failing test.

The four probes in `desktop-audit-probes.md` are pre-written failing tests for D01/D03/D05 —
copy them into `ZipExtractEngineTest.kt` / a new `VaultIndexTest.kt` instead of writing new
ones from scratch. They already reproduce the bug; verify they still fail on current
`develop` before using them as your Red step.

---

## Batch 1 — Stop deleting things users didn't ask to delete (release blockers)

The audit's hard blockers: D01, D02, D03, D09. These are the findings where an ordinary user
following the default UI path loses data. Fix these before anything else, including before
D22 below, even though D22 is also "High" — a scope bug that deletes the *wrong* files matters
more once defaults are made safer, so get the defaults safe first.

### Task 1.1 — D01: legacy archive extraction must not delete unrelated/colliding-entry ZIPs
- **File:** `composeApp/src/jvmSharedMain/kotlin/com/najdev/snapvault/downloader/ZipExtractEngine.kt`
  (`extractDownloadedArchives`)
- **Test file:** `composeApp/src/desktopTest/kotlin/com/najdev/snapvault/downloader/ZipExtractEngineTest.kt`
- **Red:** Add the two probe tests verbatim from `desktop-audit-probes.md`:
  `unrelatedArchiveWithCollidingEntriesMustBeRetained` and `thumbnailOnlyArchiveMustBeRetained`.
  Confirm both fail on current `develop` first (the audit reproduced them on `7b0cfa2`; behavior
  is unchanged since).
- **Fix direction:** pass `extractDownloadedArchives` the explicit set of archive paths this
  run downloaded, not a directory scan. Before deleting a source archive, verify every non-
  thumbnail entry was written to a distinct output path (fail/skip the archive, don't silently
  overwrite on a name collision). Do not delete an archive whose extraction produced zero
  retained files.
- **Additional case to add while you're in here:** a normal two-distinct-photo archive
  (`main.jpg`, `overlay.jpg`, different names) still extracts and is still deleted as before —
  a regression test asserting the *positive* path keeps working, since D01's fix must not turn
  into "never delete archives."

### Task 1.2 — D03: extraction startup must not delete arbitrary `.part` files
- **File:** same `ZipExtractEngine.kt` (`cleanStalePartFiles`)
- **Test file:** same `ZipExtractEngineTest.kt`
- **Red:** add probe `unrelatedPartFileMustBeRetained` verbatim.
- **Fix direction:** stage this run's own `.part` files in an app-owned per-run directory
  (or record which `.part` names this run created) instead of sweeping the whole output
  directory by extension. Add a companion test that a `.part` file this run *did* create and
  abandon (e.g. from a prior crash of the same logical run) is still cleaned up, so the fix
  doesn't regress into never cleaning up.

### Task 1.3 — D09: "nothing deleted" must not be promised for the default run
- **File:** `composeApp/src/commonMain/kotlin/com/najdev/snapvault/ui/DashboardScreen.kt`
  (dry-run label/helper text, around `DEFAULT_DRY_RUN` at line 63) and wherever the dry-run
  string is defined in `composeApp/src/commonMain/composeResources/values/strings.xml`.
- **Test file:** `composeApp/src/desktopTest/kotlin/com/najdev/snapvault/ui/DashboardScreenTest.kt`
  (create if it targets a different concern already — check for an existing one first).
- **Red:** a Compose UI test asserting the dry-run row's text is **not** the current
  "Preview only (dry run — nothing deleted)" wording, and instead reads
  "Preview duplicate removal" with helper text "Imports and metadata processing still run.
  No duplicate files will be removed." Also assert this string comes from
  `strings.xml`, not a literal (see CLAUDE.md Strings policy).
- **Fix:** update the string resource and the composable reading it. This is UI-only, but it
  is a release blocker per the audit — the current copy is materially misleading, not a
  cosmetic nit.

### Task 1.4 — D02: overlay combination must not unconditionally delete originals and overwrite output
- **Files:** `composeApp/src/commonMain/kotlin/com/najdev/snapvault/viewmodel/DashboardViewModel.kt`
  (`runCombinePhase`, the hardcoded `deleteOriginals = true`) and
  `composeApp/src/desktopMain/kotlin/com/najdev/snapvault/downloader/OverlayCombiner.kt`.
- **Test files:** `composeApp/src/desktopTest/kotlin/com/najdev/snapvault/viewmodel/DashboardViewModelTest.kt`
  and `composeApp/src/desktopTest/kotlin/com/najdev/snapvault/downloader/OverlayCombinerTest.kt`
  both already exist — extend them.
- **Red, at minimum three tests:**
  1. Combining a pair where the output path already contains *different* existing content does
     not silently overwrite it (assert original bytes at the output path survive, or the run
     reports a conflict).
  2. Metadata-copy failure during combine does not result in the source pair being deleted.
  3. `deleteOriginals` is driven by an explicit, defaulted-off option surfaced from
     `DashboardViewModel`, not a hardcoded `true` — assert the value passed to the combiner
     matches the option, including when the option is off.
- **Fix direction:** stage combiner output, verify metadata copy succeeded before any deletion,
  make `deleteOriginals` a real opt-in wired from `PipelineOptions`, default it off. This is
  the biggest task in Batch 1; it may be worth splitting into two commits (staged/safe combine,
  then the opt-in deletion default) — that's fine as long as each has its own passing test
  before the next starts.

**Batch 1 exit criteria:** all four tasks green, full `desktopTest` suite green, and you have
manually run a synthetic ZIP import (reuse the audit's fixtures under
`/private/tmp/snapvault-ui-audit-20260915/` if still present, or regenerate them) with default
settings, confirming the source ZIP and any pre-existing output files are still present
afterward.

---

## Batch 2 — Persistence and concurrency safety (D05–D08, D14, shutdown)

These don't lose data on the golden path, but they lose data under corruption, cancellation,
concurrency, or an unclean shutdown — real conditions for a desktop app people close by hitting
the red button.

### Task 2.1 — D05: a damaged index must not be silently replaced
- **File:** `composeApp/src/commonMain/kotlin/com/najdev/snapvault/VaultIndex.kt` (`read`,
  `setFavorite`, `writeMerging`)
- **Test file:** `composeApp/src/desktopTest/kotlin/com/najdev/snapvault/VaultIndexTest.kt`
  already exists (there's also a `VaultIndexConcurrencyTest.kt` — put this in `VaultIndexTest`
  unless the corruption case is concurrency-specific).
- **Red:** copy the probe `corruptIndexMustNotBeOverwrittenByFavorite` verbatim, plus a second
  case for a missing (never-created) index still initializing to an empty map correctly (so the
  fix distinguishes "missing" from "invalid" rather than treating both as failure).
- **Fix direction:** `read` must distinguish missing (→ empty map, current behavior is fine)
  from present-but-unparseable (→ fail the operation, preserve the on-disk bytes, surface an
  error the UI can show). `setFavorite`/`writeMerging`/reset must refuse to write when the read
  failed for the "invalid" reason.

### Task 2.2 — D07: same output directory needs a cross-process lock
- **File:** wherever `startSync` lives in `DashboardViewModel.kt`; new lock file mechanism,
  likely alongside `ZipExtractEngine.kt`'s staging.
- **Test file:** `DashboardViewModelTest` or a new `OutputDirectoryLockTest`.
- **Red:** two `ViewModel`/engine instances (or two invocations simulating two processes)
  pointed at the same output directory; the second must fail fast with a clear "already being
  updated" error rather than proceeding. Use disposable temp directories per CLAUDE.md test
  guidance — never touch a real user path in a test.
- **Fix direction:** exclusive `FileLock` (e.g. `java.nio.channels.FileLock`) on a sentinel file
  in the output directory, held for the run's duration, released in `finally`.

### Task 2.3 — D08: duplicate download identities must not share a temp file
- **Files:** `composeApp/src/commonMain/kotlin/com/najdev/snapvault/parser/HistoryParser.kt`,
  `composeApp/src/commonMain/kotlin/com/najdev/snapvault/downloader/DownloadEngine.kt`
- **Test file:** `composeApp/src/commonTest/kotlin/com/najdev/snapvault/downloader/DownloadEngineTest.kt`
  already exists — extend it. It's pure logic with no JVM/filesystem dependency for the
  identity/staging-name part, so it belongs in `commonTest` per CLAUDE.md's test-location table;
  only add a `desktopTest` case if real file I/O turns out unavoidable.
- **Red:** two history rows with the same `mid`/derived ID, `workers > 1`, asserting either (a)
  the run produces exactly one verified file with no error, or (b) conflicting identities are
  rejected rather than silently racing onto the same `.part` name — pick whichever contract you
  implement and assert it precisely.
- **Fix direction:** dedupe identical requests before scheduling; give each scheduled download a
  unique staging filename; no-clobber the final `atomicMove`.

### Task 2.4 — D06: cancellation must cover blocking output reads
- **File:** `composeApp/src/desktopMain/kotlin/com/najdev/snapvault/ProcessUtil.kt`
  (`waitForOrKill`, `hasDateTag`, metadata/image-fallback command readers)
- **Test file:** `composeApp/src/desktopTest/kotlin/com/najdev/snapvault/ProcessUtilTest.kt`
  (create if absent).
- **Red:** a fake child process (a small shell/script fixture, or a `ProcessBuilder` around a
  command that never closes stdout — e.g. `cat` reading from a pipe that's never closed) that
  cancellation must still terminate within a bounded time. A second test floods stderr instead
  of stdout. Both must complete (process killed, coroutine cancelled) within a short timeout —
  assert on wall-clock bound, not just eventual completion, or the test can pass by hanging the
  test runner instead of the app.
- **Fix direction:** wrap all blocking reads in `runInterruptible`, add a bounded overall
  timeout per process, guarantee kill-and-cleanup in `finally`.

### Task 2.5 — D14: resume must check verified content, not just file existence
- **Files:** `ZipExtractEngine.kt` (extraction skip check),
  `composeApp/src/commonMain/kotlin/com/najdev/snapvault/downloader/DownloadEngine.kt`
  (download skip check).
- **Test file:** `ZipExtractEngineTest.kt` / `composeApp/src/commonTest/kotlin/com/najdev/snapvault/downloader/DownloadEngineTest.kt`.
- **Red, four cases from the audit:** zero-byte existing destination is not treated as complete;
  an expired-link 200-with-HTML-body response is not saved as `.mp4`; a stalled response doesn't
  hang the run past its timeout; same filename with different byte content is treated as a
  conflict, not silently kept.
- **Fix direction:** verified-completion manifest (size/hash), content-type/magic-byte check
  before trusting a video/image extension, request/socket timeouts on `HttpClient()`.
- **Status:** three of the four cases landed — empty and error-page placeholders are no longer
  trusted by download resume, empty and folder obstructions no longer count as extracted,
  error-page bodies are refused by header and by content, and stalls (no response, or a body
  that stops) fail their own download instead of holding a worker.
- **Deferred — "same filename, different bytes":** needs the verified-completion manifest and
  cannot be approximated. The metadata pass rewrites extracted and downloaded files in place
  (`exiftool -overwrite_original`), so on any re-run a size or CRC comparison against the
  source would flag *every processed file* as a conflict. The manifest has to record what the
  pipeline itself wrote (source identity plus post-processing hash), which makes it a design
  task of its own. Also deferred: retry with backoff for transient network failures.

### Task 2.6 — Unclean shutdown must not drop pending writes
- **Files:** `composeApp/src/desktopMain/kotlin/com/najdev/snapvault/Main.kt` (window close →
  `exitApplication`), favorites cancellation in `dispose`.
- **Test file:** `DashboardViewModelTest` — this is testable at the ViewModel level even though
  the trigger is a window event; test that a stop/close request waits for or explicitly
  cancels-and-reports an in-flight favorite write rather than dropping it silently.
- **Red:** start a favorite write with an injected slow/blocking write path, trigger the
  view-model's close/stop path, assert the write either completes or is reported as
  incomplete — never silently lost with no trace.
- **Fix direction:** stop-and-wait/drain before `exitApplication`; surface pending-save state in
  the UI per the audit's "Favorite" contract row.

---

## Batch 3 — Make outcomes and controls tell the truth (D04, D10, D11, D12, D22)

Nothing here deletes data, but the app currently reports success when it shouldn't, shows
"Combined" on files that aren't, or silently resets a user's safety-relevant choices. D22 is
promoted into this batch even though the audit calls it High, because — per the audit's own
Batch 1 note — it's a *scope* bug (wrong settings get used) that matters most once Batch 1 has
made the underlying defaults safe. Fix D22 first within this batch.

### Task 3.1 — D22: navigating away must not silently reset processing options
- **File:** `composeApp/src/commonMain/kotlin/com/najdev/snapvault/ui/DashboardScreen.kt` —
  `val options = remember { PipelineOptions() }` at line 94 is scoped to the composable, so it
  resets on recomposition-losing navigation.
- **Test file:** `DashboardScreenTest.kt`
- **Red:** a Compose UI test that (a) disables combine and GPS, (b) navigates to another screen
  and back (simulate whatever triggers recomposition of `DashboardScreen` — check how
  navigation is wired in `App`/`DashboardViewModel`), (c) asserts both controls are still off
  **and** the arguments actually passed to `startSync`/Start reflect that. Add a second test
  that fresh app start still uses `DEFAULT_RUN_COMBINE = true` etc. — the fix must not weaken
  defaults, only preserve user changes.
- **Fix direction:** move `PipelineOptions` (or its values) into `DashboardViewModel` or another
  holder that survives navigation, per the audit's "immutable settings for the active run vs.
  next-run settings" framing.

### Task 3.2 — D11: Library GPS/"Combined" badges must reflect the actual output file
- **Files:** `composeApp/src/commonMain/kotlin/com/najdev/snapvault/viewmodel/DashboardViewModel.kt`
  (`runCombinePhase` metadata-map keying), `composeApp/src/commonMain/kotlin/com/najdev/snapvault/MediaScanner.kt`
  (and the desktop override).
- **Test file:** relevant `ViewModel`/`MediaScanner` test files under `desktopTest`.
- **Red:** after a run with combining **disabled**, assert the resulting Library entry does not
  report `hasOverlay`/"Combined" as true for the uncombined main file (this is the exact live
  repro from the audit's Pass 5). After a run with combining enabled and successful, assert the
  combined output *does* carry transferred metadata/favorites under its real final filename.
- **Fix direction:** separate "source pair present" from "combine completed" as distinct fields;
  transfer metadata keys from `-main`/`-overlay` to `result.outputPath` after a successful
  combine, not before.

### Task 3.3 — D10: completion status must surface warnings and empty imports
- **File:** `DashboardViewModel.kt` (`pipelineFailureCount`, `runZipPipeline`)
- **Test file:** `DashboardViewModelTest`
- **Red, from the audit's listed cases:** missing index write permission, metadata-copy failure,
  archive extraction warning, wrong/unsupported ZIP (only `notes.txt`), and mixed
  supported/unsupported media — each must produce a non-silent, non-"success" outcome (either a
  surfaced warning count or an explicit "0 memories imported" error), not the current
  "100% / Pipeline Complete / Sync complete!" for zero imported memories.
- **Fix direction:** typed phase outcomes; an explicit no-importable-media error; incorporate
  unmatched ZIP entries, legacy archive warnings, index-save failure, and per-pair metadata-copy
  warnings into the reported failure/warning count.

### Task 3.4 — D12: settings/controls must not suggest effects they don't have
- **Files:** `DashboardScreen.kt` (checkbox enablement during a run), Library/Settings output
  folder picker, "Reset Download Index" label and its discarded Boolean result in `App`.
- **Test file:** `DashboardScreenTest.kt` plus wherever `App`'s reset handling lives.
- **Red, pick the sub-issues you're fixing and write one assertion each:** pipeline checkboxes
  are disabled (or clearly "applies to next run") while a run is active; changing the output
  folder mid-run doesn't silently diverge Library from the active pipeline's captured `outDir`;
  Reset's result is surfaced to the user (success/failure), not discarded.
- **Fix direction:** as described per bullet in the audit's D12. This task is naturally
  splittable into multiple small commits — do that rather than one large one.

### Task 3.5 — D04: dedupe must respect favorites and owned-asset scope
- **File:** `composeApp/src/commonMain/kotlin/com/najdev/snapvault/downloader/Deduplicator.kt`
  (`deduplicateFolder`)
- **Test file:** existing `Deduplicator` test under `commonTest` — extend it.
- **Red:** two byte-identical files where one is favorited; assert the favorited copy is never
  the one deleted, regardless of lexicographic order. Add a case restricting candidates to
  recognized media (not arbitrary regular files).
- **Fix direction:** consult favorites before choosing which duplicate to keep; restrict the
  scan to owned/recognized media.
- **Status:** landed — favorited copies are never deleted (read from disk at dedupe time), and
  only formats the Library shows are candidates.
- **Deferred — trash/quarantine instead of permanent delete:** the audit also recommends moving
  duplicates to the OS trash or a quarantine folder. That is a new capability with its own
  per-platform questions (Desktop.moveToTrash support, quarantine location and cleanup), not a
  scope fix; dry run remains the default safeguard until it exists.

---

## Batch 4 — Resource budgets and tool integrity (D13, D15, D19)

Lower urgency than Batches 1–3 (these degrade rather than destroy), but still pre-promotion
work per the audit. Use quota-limited fixtures — the audit is explicit that a disk-filling or
memory-exhaustion test must never run against a real host.

### Task 4.1 — D19: thumbnails must not go stale or silently blank
- **File:** `MediaScanner.kt` (`loadThumbnail`, desktop video-thumbnail generation)
- **Test file:** `composeApp/src/desktopTest/kotlin/com/najdev/snapvault/MediaScannerTest.kt`
  already exists — extend it.
- **Red, per the audit's regression list:** overwrite a source file under the same name and
  assert the cached thumbnail is invalidated/regenerated, not served stale; a corrupted cached
  thumbnail is detected and regenerated; a thumbnail-generation timeout removes its partial
  output rather than leaving it; HEIC/WebP/MKV/M4V inputs get a consistent
  supported/"preview unavailable" outcome rather than falling through the format gap the audit
  describes.
- **Fix direction:** source-versioned cache key (mtime/size), staged thumbnail writes, cleanup
  on timeout/failure, format-list parity, visible fallback string (add to `strings.xml`).

### Task 4.2 — D15: extracted tools must be versioned and integrity-checked
- **File:** `composeApp/src/desktopMain/kotlin/com/najdev/snapvault/BinaryExtractor.kt`
  (`resolveCommand`)
- **Test file:** `composeApp/src/desktopTest/kotlin/com/najdev/snapvault/BinaryExtractorTest.kt`
  already exists — extend it.
- **Red:** simulate an old cached tool at `~/.snapvault/bin/<name>` (use a temp dir override,
  not the real home directory) alongside a newer bundled resource; assert resolution prefers
  the verified/current version. Assert interrupted extraction (partial file present) is
  detected and re-extracted rather than treated as installed.
- **Fix direction:** versioned tool directories, recorded checksums, staged extraction with
  atomic install, expose actual resolved path/version in Settings (ties into D17's "under-
  specified provenance" but the version-exposure UI is a small, separate, testable piece).
- **Status:** landed — tools install per bundled-archive SHA-256 under `~/.snapvault/bin/<tool>/`,
  via staged extraction with a completion marker and rename, with entry-path containment and
  cleanup of superseded versions. A tool on the user's PATH still takes precedence.
- **Deferred:** showing the resolved tool path/version in Settings; and removing the flat
  `~/.snapvault/bin/{ffmpeg,exiftool,exiftool-dist}` left by earlier builds, which is no longer
  read but still occupies disk.

### Task 4.3 — D13: bound resource consumption
- **Files:** `computeWorkerCount()` is an `expect`/`actual` — declared in
  `composeApp/src/commonMain/kotlin/com/najdev/snapvault/ThemePreferences.kt`, implemented per
  platform in `desktopMain`/`androidMain`/`iosMain`'s `ThemePreferences.kt` (desktop's scales
  with 75% of CPUs, per the audit), and consumed in `DashboardViewModel.kt:78`. Image-decode
  concurrency lives in `OverlayCombiner.kt`. Legacy download's buffered-vs-streaming request
  path is in `DownloadEngine.kt`.
- **Test file:** desktop tests using small, explicit, low-memory fixtures — never a real
  disk-filling test, per the audit.
- **Red:** assert `computeWorkerCount` has an explicit upper cap regardless of CPU count;
  assert a configured byte/entry quota rejects an oversized synthetic ZIP fixture before
  extraction proceeds; for the legacy-download streaming claim, add a bounded-memory test for
  the pinned Ktor version using `prepareGet(...).execute { }` and assert memory stays bounded
  for a large synthetic response (use a small artificial cap in the test, not a real large
  file).
- **Fix direction:** upper-bound worker count; add byte/pixel/entry quotas; switch legacy
  download to Ktor's streaming `prepareGet` pattern if the bounded-memory test proves the
  current buffered path doesn't stream.

---

## Batch 5 — Release pipeline, provenance, and privacy copy (D16–D18)

These are process/CI and copy fixes, not app logic — lower TDD applicability, but still needs
verification before the next tag.

- **D16:** add a final gate job in the release workflow that depends on every OS matrix job
  succeeding before publishing; push the tag only after tests pass; add a `workflow_dispatch`
  main-only guard and a concurrency group. Verify by dry-running the workflow (or a scoped
  branch) and confirming a deliberately-failed matrix leg blocks the publish job — this is a CI
  config change, verify with a real (throwaway) workflow run, not a unit test.
- **D17:** add a machine-readable manifest (version/hash/build-source) for shipped native
  binaries; add hash verification to the FFmpeg fetch scripts for macOS x64/Windows; make
  Intel/Apple Silicon packaging explicit in the release matrix and verify actual DMG
  architecture post-build.
- **D18:** update README's "entirely offline" claim to the audit's suggested language
  ("ZIP imports run locally. Legacy link imports require internet access. Linux requires
  FFmpeg and Perl."); add a pre-import GPS-embedding disclosure to the UI (new string in
  `strings.xml`, plus a `DashboardScreenTest` assertion it renders before Start is enabled);
  redact URL query tokens/personal paths in copyable support logs (testable — assert a
  formatted log line does not contain the raw token, in whatever module formats logs for
  export); relabel system-player video launch as "Open in your video player" and surface
  launch errors instead of a timed "Opening" message (testable at the ViewModel/UI level).

---

## Batch 6 — Product requirements surfaced by the audit (D20, D21, restart/UX)

These are new feature work the audit recommends, not bug fixes — treat them as normal
new-behaviour tasks under CLAUDE.md (test the expected path and the failure/edge path), and
confirm scope/priority with the user before starting, since they're larger than the rest of
this plan.

- **D20 — disk-space planning + optional "delete source ZIP after verified import."** Needs a
  design pass before TDD starts: what "peak space" estimate is shown, what "safely imported"
  means as a durable per-archive commit record. The audit lists required failing tests in its
  D20 section (insufficient space at preflight/mid-write, same/different volume, malicious
  entry sizes, interrupted verification, crash before/after commit, cleanup default off,
  etc.) — use that list directly as your test plan once the design is settled. Default this
  feature **off**.
- **D21 — native decorated macOS window for AeroSpace compatibility.** Candidate fix: stop
  using `undecorated = true` / custom traffic lights on macOS, keep the native title bar and
  fullscreen control. Write a failing test asserting the macOS window is decorated with a
  native fullscreen affordance (whatever's mechanically assertable — likely at the AWT/Compose
  window-config level, not a UI screenshot test), then do an AeroSpace A/B by hand per the
  audit's resume checklist before calling it fixed. This is desktop/macOS-only; confirm Windows
  and Linux window behavior are unaffected by the change (they should already be decorated —
  verify, don't assume).
- **Restart doesn't remember the library folder.** Not yet a numbered finding. Low-risk,
  testable addition: persist the last output folder (and maybe source) and offer to reopen it,
  with clear handling when the path no longer exists/is unmounted. Write the failing test for
  "app remembers last output folder across restart" plus "moved/missing folder shows a clear
  error, not a crash or silent empty state" before implementing.

---

## Explicitly out of scope for TDD (manual acceptance gates)

The audit is clear these need clean-machine/hardware validation the test suite cannot
substitute for. Track them as manual release checklist items, not automated tests:

- Clean installed DMG/MSI/DEB behavior on each OS.
- Minimum supported OS versions; Windows path/permission behavior at runtime.
- Antivirus/signing/notarization experience on an unmodified machine.
- Real VoiceOver/screen-reader testing; actual window resize/HiDPI behavior (computer-use
  automation was unreliable for both per the audit — don't trust an automated resize test as a
  substitute).
- Power-loss and network-volume durability.
- Sustained huge-library resource tests (run these in a disposable VM/container with quotas,
  never on a real host, if you do run them).
- Native/dependency binary vulnerability inventory.

Run these once Batches 1–5 are merged, using the audit's Pass 6 "remaining work" checklist
(search, sort, keyboard arrows/Enter/Escape/tab focus, reveal-in-Finder, compact/medium resize,
pending-save close, cancellation) as the walkthrough script.

---

## Suggested execution order

1. Batch 1 (blockers) — do not proceed to promotion planning until this is green and manually
   verified against a synthetic import.
2. Batch 2 (persistence/concurrency).
3. Batch 3, task 3.1 (D22) first, then the rest of Batch 3.
4. Batch 4.
5. Batch 5 (can run in parallel with Batch 4 — it's CI/copy, not app logic).
6. Batch 6, only after confirming scope/design with the user for D20 specifically.
7. Manual acceptance gates.

This mirrors the audit's own "Release decision and order of work" section; the batches above
just attach concrete test files and Red/Green steps to each item in it.
