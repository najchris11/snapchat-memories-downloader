# Desktop release audit — 2026-09-15

## Scope and status

Completed source audit with explicit validation limits below. Target: `develop` at `7b0cfa2`, compared with locally available `origin/main` at `28f03ca` (local `main` is stale at `67823d5`). Working tree initially clean. Remote refs have not been refreshed. Desktop only; shared code is included where the desktop uses it. No production changes requested or made. This document is maintained across review passes; see Pass 5 for the latest live UI findings and continuation state.

Read `AGENTS.md` and `CLAUDE.md`. Existing comments and previous audit plans are leads, not proof. Findings distinguish direct source evidence, reproduced behavior, and untested risks. An audit cannot certify that every machine or input is safe.

## Initial findings — filesystem review

### D01 — High: legacy extraction can destroy unrelated ZIP archives

`ZipExtractEngine.extractDownloadedArchives` enumerates **every ZIP in the output directory**, rather than the successful downloads for this run. It reduces each non-thumbnail entry to `<archive-base>-main.<extension>` or `-overlay.<extension>`. Two distinct same-extension entries collide; the second is reported as skipped, and `allOk` remains true. The archive is then permanently deleted. Archives with only thumbnails or extensionless entries also reach deletion without preserving their contents. Selecting Downloads or the source-export directory as output exposes unrelated archives. Fix: pass explicit owned archive paths, preflight entry-to-output uniqueness, verify all required contents, and retain source archives by default. Required regression: two different `.jpg` entries both survive, unrelated ZIPs remain byte-identical, thumbnail-only ZIP is retained.

### D02 — High: overlay combination overwrites existing output and always deletes source pairs

`DashboardViewModel.runCombinePhase` unconditionally passes `deleteOriginals = true`. `OverlayCombiner` discovers all matching pairs already present in output. ImageIO writes directly to the final output; FFmpeg uses overwrite (`-y`). There is no existing-output conflict check, ownership manifest, staged commit, or recoverable deletion. A re-import can overwrite a previously edited combined image/video; cancellation or failure can truncate that existing output. Metadata-copy failure does not prevent original deletion. Fix: stage and validate derivatives, preserve existing different content, retain originals by default, and make deletion explicit and recoverable.

### D03 — High: extraction startup deletes arbitrary `.part` files

`ZipExtractEngine.cleanStalePartFiles` deletes every regular file ending `.part` in the chosen output directory before extraction. No ownership, age, or active-run check. Other software's downloads (or another SnapVault instance's current extraction) can be deleted. Fix: app-owned per-run staging directory plus ownership/locking; never infer ownership from extension alone.

### D04 — Medium: deduplication scope includes non-media and user-owned files

`Deduplicator.deduplicateFolder` hashes all regular files except `vault_index.json` and `.part`, and permanently deletes all but the lexicographically first identical copy when dry run is disabled. No manifest or media whitelist; favorites are not consulted. Byte equality protects content but not a user's organization or favorite reference. Dry-run default is a useful safeguard, not a scope guarantee. Fix: restrict candidates to owned assets, preview concrete deletions, preserve favorites, and use trash/quarantine.

## Work log

- Pass 1: inventory, branch comparison, project instructions, extraction/download/dedupe, overlay and metadata subprocess paths, index persistence, release workflow and runtime scripts read. Initial findings recorded above; reproductions and UI tracing next.

## Pass 2 — verified failures, state, and average-user expectations

The four isolated probes in [desktop-audit-probes.md](desktop-audit-probes.md) **all failed for the expected safety assertion**, without changing production code. D01 reproduced twice: a two-JPEG archive was deleted with only the first JPEG preserved, and a thumbnail-only archive was deleted with no output. D03 reproduced even with an empty extraction task list. D05 below reproduced by attempting one favorite write. Probes were removed from the active test source set and preserved verbatim in that document; these are not fixes or passing regression coverage.

### D05 — High: a damaged index is silently replaced, losing recoverable favorites (develop feature)

`VaultIndex.read` treats missing, unreadable, malformed, and unsupported data identically as an empty map. `setFavorite` and `writeMerging` then replace the original with this reduced state; reset can delete it outright. Reproduced: malformed index replaced with only `new.jpg` after one favorite toggle. Atomic writing solves torn writes during ordinary operation but does not solve corruption recovery. Distinguish missing from invalid; preserve the invalid bytes, fail closed on mutation, and offer recovery from a versioned backup. Required regression: corrupt/unsupported index remains untouched and the UI reports the failure.

### D06 — High: process cancellation does not cover blocking output reads

`ProcessUtil.waitForOrKill` only kills a child when `waitFor()` throws `InterruptedException`. Metadata and image-fallback commands first block in `inputStream.bufferedReader().readText()`; `hasDateTag` is not wrapped in `runInterruptible` at all. There is no general command timeout or cancellation-owned process cleanup. A stuck child can therefore keep Stop at “Stopping…” and keep operating on files. This is a source-supported risk, not a reproduced hang. Extraction `input.copyTo` and per-file hashing likewise check cancellation only between large operations. Use one process runner with bounded concurrent drain, timeout, cancellation cleanup in `finally`, and verified child termination; test a fake child that never closes stdout, one that floods stderr, and cancellation during a large copy. Java documents that forcible destruction may not complete immediately: [Process API](https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/lang/Process.html).

### D07 — High: same output directory has no cross-process ownership lock

`startSync` prevents ordinary double starts in one view model, but no output-directory file lock or single-instance guard exists. Extraction's move lock, the FFmpeg semaphore, and `VaultIndex` mutex are process-local. Two instances can clean each other's staging files, share download `.part` paths and `vault_index.json.tmp`, overwrite combined media, or lose favorite updates. Fixed temporary names and overwrite moves also need a symlink policy; existing symlink media can route processing outside the selected folder. No symlink escape or cross-process corruption was executed in this audit. Require an exclusive writer lock plus canonical-path/symlink checks, with a clear “This library is already being updated” message. Test two processes and symlinked staging/output paths in disposable directories.

### D08 — Medium: duplicate download identities can share a temporary file

`HistoryParser` preserves repeated history rows and derives ID from `mid`; `DownloadEngine.downloadAll` assumes IDs never collide and shares a start-of-run name snapshot. Repeated same-date/same-ID rows can concurrently truncate/write/move the same `<filename>.part`; final `atomicMove` can replace existing content. Deduplicate identical requests before scheduling; reject conflicting identities, and use unique staging files plus no-clobber commit. Regression: repeated rows with workers > 1 produce one verified file without errors; conflicting links must not silently choose a winner.

### D09 — High: “nothing deleted” is an unsafe promise for the default run

`DEFAULT_RUN_COMBINE`, `DEFAULT_RUN_DEDUPE`, and `DEFAULT_DRY_RUN` are all true. The dry-run option is visually nested under dedupe, but its wording is “Preview only (dry run — nothing deleted)”. Only dedupe honors it. Combine deletes main/overlay originals; legacy extraction deletes archives; ZIP extraction deletes `.part` files. An ordinary user can reasonably infer a non-destructive preview. Change the behavior to preserve sources and scope the wording to **“Preview duplicate removal”**, with helper text **“Imports and metadata processing still run. No duplicate files will be removed.”** Do not claim a global preview unless every stage truly honors it. This is a release blocker alongside D01–D03, not merely copy polish.

### D10 — Medium: completion status omits important warnings and empty imports

`pipelineFailureCount` does not incorporate unmatched ZIP files, legacy archive warnings, index-save failure, per-pair metadata-copy warnings, combine date-fallback errors, or skipped unsupported combinations. `runZipPipeline` accepts zero recognized memories, can still run folder-wide combination/dedupe, and ends with success. ZIP readers erase the distinction between invalid/unreadable archives and empty ones during discovery. Require an explicit no-importable-media error, typed phase outcomes, a persistent result report with imported/skipped/failed counts, and retryable failures. “Complete” must not mean merely “the coroutine returned.” Test missing index write permission, metadata-copy failure, archive extraction warning, wrong ZIP, and mixed supported/unsupported media.

### D11 — Medium: Library GPS and “Combined” badges do not describe resulting files reliably

Metadata maps are keyed by `-main`/`-overlay` names; `runCombinePhase` never transfers them to `result.outputPath`. Sources are then deleted. `MediaScanner` looks up the final basename and defaults missing flags to false, so successful combined files can appear untagged/uncombined. Conversely, `hasOverlay` is set from the existence of a pair before combination, yet the inspector renders it as “Combined” even if combining is disabled or fails. These flags are cached pipeline claims, not metadata read-back. Make source-overlay presence and completed-combine status different fields, transfer metadata/favorites to derivatives, and assert the post-run Library state with real filenames.

### D12 — Medium: settings and controls suggest effects they do not have

- Pipeline checkboxes remain editable during a run, but `startSync` already captured their values. Disable them while running or explicitly label changes as applying to the next run.
- Library and Settings can change the output folder during a run even though Dashboard disables its picker. The active pipeline continues using captured `outDir`, while Library/favorites use the new folder. Guard all entry points consistently or show separate active-run and browsing destinations.
- “Reset Download Index” says the next run reprocesses everything; resume is determined by file existence, not this index. Reset does not force downloads/extraction. Rename to describe cache clearing, or implement an explicit verified reprocess operation.
- Reset's Boolean result is discarded by `App`; no visible success/failure acknowledgement.
- Window close calls `exitApplication` directly; queued favorites are cancelled in `dispose`, and there is no stop-and-wait/drain workflow. A just-clicked heart or an in-flight output may not be saved. Show pending-save state and finish/cancel safely before exit.

### D13 — Medium: resource budgets are insufficient for large exports

There are no uncompressed ZIP-byte/entry quotas, free-space preflight or reserve, image-dimension limits, or user-configurable concurrency budget. Extraction and metadata schedule one coroutine per item, ZIPs can all remain open, logs grow for the run, JSON/history is read fully into memory, and image combination decodes full images concurrently. `computeWorkerCount` scales with 75% of CPUs without an upper cap; thumbnail cache is count-limited but full-image decoding remains unbounded. Legitimate large exports or malformed media can exhaust disk/RAM and make the machine unresponsive. Do not test a disk-filling bomb on a user's host: use quota-limited fixtures/containers and low-memory test processes. Add byte/pixel limits, bounded task queues, disk checks, and throttling.

Legacy download is also not demonstrably streaming: it uses `client.get/post` before `bodyAsChannel`; Ktor's documented large-response pattern is `prepareGet(...).execute { ... }` so the response is processed without saving it first. [Ktor response documentation](https://ktor.io/docs/client-responses.html). Verify the pinned 3.5.0 implementation with a bounded-memory large-response test; replace buffered request execution if confirmed. No memory-exhaustion test was run.

### D14 — Medium: resume checks file existence rather than successful content

ZIP extraction skips any existing pathname; downloads skip matching names before networking. Zero-length, corrupt, directory, or wrong-content destinations can be treated as complete. No manifest of verified bytes or source identity exists. A 2xx HTML/error body can be saved as `.mp4` because content type has a video fallback; there is no media-signature validation. No explicit retry/backoff or request/socket timeout configuration is installed in `HttpClient()`. Require verified completion records, content validation and bounded network retries, with a recoverable conflict state. Regression: zero-byte existing file, expired link returning HTML 200, stalled response, and same filename with different bytes.

## Pass 3 — packaging, trust, privacy, and release coverage

### D15 — High: existing extracted tools are never upgraded or integrity-checked

`BinaryExtractor.resolveCommand` prefers any PATH command, then any executable at `~/.snapvault/bin/<name>`, before looking at bundled resources. There is no version/hash comparison. Updating SnapVault therefore does not update a previously extracted FFmpeg/ExifTool, including a vulnerable or partially extracted one. Extraction writes directly into shared final paths, is only synchronized within one process, and accepts resource entry paths without containment checks. The resource ZIP is shipped by the application, so this is **not** evidence that a normal Snapchat ZIP can exploit that extractor. Use versioned tool directories, recorded checksums, staged extraction and verified installation; expose actual path/version in Settings. Test upgrading an old tool cache and interruption halfway through extraction.

### D16 — High: release publication is not gated on the whole matrix succeeding

Each OS matrix job runs its own tests/package/upload independently. One successful job can publish the GitHub release while another fails; there is no final all-platform gate, draft staging, installer smoke test, or checksum manifest. The version/tag is pushed before tests. `workflow_dispatch` also allows branch selection, but the job pushes `main` unconditionally and has no explicit main-only guard or concurrency group. `develop` still carries `app.version=1.0.11` while local `origin/main` is a v1.0.13 release commit: merge/version reconciliation needs deliberate verification before the next bump. Fix: validate ref/version, serialize releases, build and test all intended artifacts, stage them, then publish once from a final dependent job. Do not advertise an installer merely because source tests passed.

### D17 — Medium: runtime provenance and platform support are under-specified

Preparation scripts fetch FFmpeg from mutable release URLs (macOS x64/Windows) without hash verification. The repository carries binary ZIPs, but no machine-readable version/hash/build-source manifest or automated dependency/native-binary vulnerability check was found. This audit did **not** establish a current CVE inventory and makes no “no known vulnerabilities” claim. Preserve exact tool versions, hashes, build configuration and corresponding source alongside each release; scan dependencies and shipped native binaries before publication. Notice files exist and are packaged, but their existence alone does not verify source correspondence.

The release matrix has one `macos-latest` runner and no explicit separate Intel/Apple Silicon packaging jobs. Bundling both FFmpeg architectures does not make the JVM/app universal. Make architecture explicit in assets and requirements; verify the actual DMG architecture. Linux FFmpeg and Perl remain external prerequisites. No clean Windows/Linux/Intel-Mac installer was exercised here.

### D18 — Medium: installation and privacy promises need plain-language boundaries

README opens with “entirely offline … no manual setup”, but legacy mode performs network downloads and Linux needs packages. Say **“ZIP imports run locally. Legacy link imports require internet access. Linux requires FFmpeg and Perl.”** Unsigned delivery is documented and no signing/notarization pipeline is configured; broad advertising will predictably put ordinary users in front of OS security warnings. Prefer signing and clean-machine installation evidence over treating bypass commands as onboarding. Existing code-signing roadmap contains time-sensitive vendor/pricing/platform assertions that were not revalidated in this audit.

ZIP matching writes precise GPS by default; tell users before import that location is embedded in resulting files and may accompany shared copies. No analytics/upload subsystem was found in the desktop paths reviewed, but this is not a traffic-capture privacy assessment. Legacy URLs accept arbitrary `http...` strings, with no HTTPS/host policy, and parser diagnostics can include raw download-link snippets in copyable logs. Redact URL query tokens and personal paths before support-log export; distinguish ordinary Snapchat-origin networking from arbitrary supplied hosts. Library video playback opens the system player rather than playing inside SnapVault: label it “Open in your video player” and surface launch errors, not a timed “Opening” message that resets regardless of outcome.

### D19 — Medium: thumbnails can be stale or permanently blank after interruption

`MediaScanner.loadThumbnail` accepts any existing `.thumbnails/<filename>.jpg` without source mtime/size/content validation. A stale thumbnail survives an overwritten media file; a failed/partial thumbnail returns null without eviction/retry. Video thumbnail timeout does not remove partial output and does not check exit status; stdout/stderr are not drained. Supported scan formats exceed supported thumbnail formats. A full image is decoded for an ordinary photo thumbnail, adding to D13. Use a source-versioned cache, staged thumbnail writes, invalid-cache recovery, consistent supported formats, and a visible “Preview unavailable — open original” fallback. Regression: overwrite source under same name, corrupt cached thumbnail, timeout during generation, HEIC/WebP/MKV/M4V inputs.

## Recommended user contract

Safety must come from behavior first; explanatory text is not a substitute for scoped writes and retaining originals.

| User-facing concept | What an ordinary user should be able to rely on | Suggested presentation |
|---|---|---|
| Import source | Reading an export never deletes it | “Your source ZIPs are kept unchanged.” Only use after verified in both modes. |
| Destination | A dedicated library, not permission to manage every file in an arbitrary folder | Offer “Create SnapVault library”; detect non-empty/unowned folders and describe conflicts before work. |
| ZIP action | Local extraction, not another download | “Import ZIPs”; legacy action “Download memories”. |
| Overlay combination | Makes a flattened derivative, can change encoding/quality, retains source by default | “Combine photo and video overlays”; separate opt-in source cleanup with exact file preview. |
| Duplicate preview | Only previews duplicate cleanup | “Preview duplicate removal”; do not say globally “nothing deleted”. |
| Metadata | Writes capture date/time and optionally location into output files | Explain GPS sharing implications and whether times are exact or date-only. |
| Stop | Stops all writers before another run begins | “Stopping safely…” followed by what was saved and what needs retrying. |
| Finished | Every selected source has an accounted-for outcome | Counts for imported, verified existing, unsupported, failed, and omitted metadata; open output/retry failures actions. |
| Favorite | Persists or visibly fails, including close/restart | Pending/saved/error state, preserved across derivative creation and cleanup. |
| Reset | Says exactly which cached information changes | “Clear processing metadata cache”; retain favorites and do not imply forced redownload. |
| Library | Browsing a folder does not silently redirect a running import | Separate “Active import destination” from browsing, or lock destination changes while running. |

## Positive safeguards verified in source and existing tests

- Deduplication defaults to preview; hash comparison is SHA-256 with a size prefilter and deterministic keep selection.
- Ordinary ZIP extraction stages to unique temporary files, verifies declared size, and serializes in-process moves; parsed media names are restricted to a safe leaf-name grammar. A generic user-ZIP path traversal was not demonstrated.
- Legacy IDs are constrained from `mid` or generated as a hash, rather than taking arbitrary URL paths as output filenames.
- Runtime commands use argument arrays rather than interpolating imported text into a shell command.
- FFmpeg video work is serialized within a combiner; hardware encoders are tested with a real probe and have software fallback.
- Normal index writes use atomic replacement; same-process favorite writes are ordered and pipeline merge preserves on-disk favorites.
- UI keyboard navigation, contrast, semantics, empty states, and favorites have substantial new tests on develop. These improvements should be retained while fixing the remaining safety gaps.

## Verification and limits

- `./gradlew :composeApp:desktopTest`: baseline passed, then passed again after temporary probes were archived; final XML totals **208 tests, 34 classes, 0 failures, 0 errors, 0 skipped**.
- `./gradlew :composeApp:desktopTest :composeApp:compileKotlinDesktop detekt`: passed. Desktop compiled during baseline run; final explicit compile was up-to-date. Warnings: expect/actual class beta and Gradle deprecations.
- Four temporary safety tests: **4 failed for the intended assertions**, proving D01/D03/D05. No artificial production break was necessary because each test failed against the existing unsafe behavior. No behavior was changed; these are audit reproductions, not fixes.
- `./gradlew :composeApp:run -PisDebug=false`: launched the current checkout. Computer-use inventory saw the `MainKt` Java process, but both its reported app identifier and display name returned `Invalid app`, preventing screen-level inspection through the available UI tool. Usability findings therefore come from source, strings, and automated Compose tests, **not** a claimed visual walkthrough or screen-reader test.
- No mobile changes; Android/iOS builds were not run because the only retained changes are audit Markdown and the requested release scope is desktop.
- No personal export was imported, no real user media was deleted, and no destructive disk/resource exhaustion test was run. No release published, branch merged, or commit created.
- Not verified: clean installed DMG/MSI/DEB behavior; minimum supported OS versions; Windows paths/permissions at runtime; antivirus/signing experience; real screen-reader/HiDPI/resize behavior; power loss/network-volume durability; sustained huge-library/resource tests; native binary vulnerabilities. These remain explicit release-validation work, not assumed passes.

## Release decision and order of work

**Do not expand public promotion yet.** There are concrete data-loss paths; a passing existing suite is insufficient. No mechanism for physical computer damage was identified, but deleting personal archives, overwriting edits, losing favorites, exhausting disk/memory, and leaving writers running are material user harms.

1. Block destructive scope: D01–D03 and D09. Restrict operations to owned files, retain archives/originals, stage derivatives, and make preview wording truthful.
2. Protect persistence/concurrency: D05–D08, verified resume/content handling (D14), and stop/close behavior. Add the failing regressions first, then implement fixes under the repository's required red/green policy.
3. Make outcomes and controls truthful: D10–D12; update library metadata mapping, folder ownership, option locking and completion reporting. Reconcile favorites with dedupe (D04).
4. Bound resource consumption and validate tool installation: D13/D15/D19. Use constrained environments for pathological input tests.
5. Gate complete release artifacts (D16), record runtime provenance and architecture (D17), and complete onboarding/privacy clarification (D18).
6. Run clean-machine installer acceptance on every advertised OS/architecture: fresh install with no developer tools, synthetic ZIP and legacy import, rerun, cancellation/close, missing dependencies, non-empty destination, low disk, corrupt media, favorites/restart, uninstall retaining library, and upgrade with an old runtime cache. Record artifact hashes and exact outcomes.

## Checkpoint / continuation

Completed three review passes and preserved the main evidence. Production code remains untouched. Findings D01–D04 are inherited pipeline issues (the relevant files are unchanged relative to `origin/main`); D05 and pending-favorite shutdown concerns particularly affect the new develop favorites functionality. D06–D19 cover current behavior; no claim that every one was introduced on develop.

The user requested a pause at 90% of their five-hour usage allowance. No usage-meter reading is available to this session; pause immediately on a user-provided threshold notification and resume from this file. Next work should be fixing release blockers in small tested changes, followed by the installer/visual validation above. This audit does not authorize publishing a release.

## Pass 4 checkpoint — paused at user's usage limit

User explicitly requested a pause at the usage limit. Stop here; do not resume testing until asked. This section supersedes the earlier computer-use limitation for the packaged macOS app, but does not imply the remaining walkthrough is complete.

### Completed since the initial audit

- Retried the raw Gradle Java app: computer-use inventory listed `MainKt` / `net.java.openjdk.java`, but attachment still returned `Invalid app`. Stopped that development run.
- Rebuilt the native app from current develop with `:composeApp:createReleaseDistributable -PisDebug=false`. Local JBR 21.0.7 failed `jlink` with a `java.xml` module-hash mismatch. Retrying with `-Pcompose.javaHome=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` **succeeded**. This is evidence of a local JDK packaging problem, not proof CI has the same failure.
- Opened the freshly built app through computer use at `composeApp/build/compose/binaries/main-release/app/SnapVault.app`. Attachment worked. This validates launch of a locally built app bundle, not DMG installation, notarization, or a clean-machine experience.
- Visually inspected the initial dark Dashboard at its default window size. Source/destination, options, progress and disabled Start were visible. Confirmed D09's “nothing deleted” wording and D18/user-contract mismatch: ZIP mode still says “Start Download”, “Downloading”, and “Ready to download”. Destination truncates in the visible field; accessibility text contains its full path.
- Opened the empty Library: it clearly explains that selecting the output folder controls both pipeline writes and Library contents, and offers “Set output folder”. This is a positive onboarding behavior.
- Opened Settings: ExifTool and FFmpeg both reported Detected on this developer machine; actual executable paths/versions were not displayed. Reset promises reprocessing, supporting D12. No theme/layout preferences were changed.
- Created synthetic fixtures under `/private/tmp/snapvault-ui-audit-20260915`: `synthetic-memories.zip` (three colored main PNGs and one overlay), `wrong-export.zip` (notes only), plus `output/` and `empty-output/`. No personal exports or media were used.
- Used native file pickers to select the synthetic ZIP and isolated `output/` folder. Disabled **Merge Video Overlays** before starting; screenshot verified its switch was off. Kept metadata, precise matching, duplicate scan and duplicate preview enabled. This run did not intentionally request deletion.
- Clicked Start. The latest observed UI showed **100%, Pipeline Complete, [SUCCESS] Sync complete!** The run finished before the next observation. Logs and resulting files have **not yet been inspected**; this is a UI-reported outcome, not verification of complete/correct output.
- The accessibility tool's checkbox text did not expose on/off changes, while the screenshot did. Treat this as a tool/AX observation to investigate, not yet a proven VoiceOver defect.

### Current state

The packaged SnapVault app remains open on Dashboard after the synthetic import. Source: `/private/tmp/snapvault-ui-audit-20260915/synthetic-memories.zip`; destination: `/private/tmp/snapvault-ui-audit-20260915/output`. No pipeline was visibly running at the last observation. The user interrupted immediately after the completion observation. No app shutdown, fixture cleanup, or further testing was attempted after the pause request. Production code is unchanged; retained deliverables are audit Markdown. Build outputs changed locally as part of packaging.

### Resume checklist

1. Check current git/app state and reread this checkpoint. Attach through the absolute `.app` path above (raw Java attachment failed). Get fresh AX IDs; they change, especially after a full tree refresh. Do not reuse old element numbers.
2. Open View Logs and inspect the synthetic output on disk. Verify extraction count, metadata fallback, source ZIP preservation, no accidental deletions, and the index's contents. Compare the clean “Complete” status against any warnings.
3. Inspect the populated Library: thumbnails, main/overlay presentation, inspector truthfulness (D11), search, filters, sorting, favorites persistence, keyboard arrows/Enter/Escape, preview and reveal-in-Finder behavior. Use only synthetic files.
4. Test wrong/empty ZIP import in `empty-output/`, then check completion/error wording. Avoid folder-wide operations on personal directories.
5. Exercise resize/compact layout, tab order/focus, clipping and readable control labels. Theme/layout settings persist: coordinate any preference changes and restore the user's original values. Actual VoiceOver and other OSes remain untested.
6. Test re-import, safe cancellation/close, and cross-screen state using controlled fixtures; account for the known destructive combination and archive-cleanup behavior before invoking any deleting UI action. No default combined run was performed in this pass.
7. Add new confirmed findings or promote existing source findings to reproduced status, and update the verification-limit section. Clean installed DMG/MSI/DEB checks, Windows/Linux/Intel hardware, signing, resource stress and native vulnerability inventory remain outstanding.

Paused as requested. No further work should be inferred from this checkpoint.

## Pass 5 — resumed 2026-09-16: storage planning and native macOS behavior

User resumed the audit and identified **AeroSpace** as the tiling window manager. User requests disk-space visibility and an optional space-saving workflow that can delete processed input ZIPs. These are product requirements for follow-up implementation, not permission to delete existing personal archives during this audit.

### D20 — High for large imports: users cannot plan peak disk usage or safely reclaim processed ZIPs

Extends D13. Current Library “Storage Usage” only totals recognized media files (synthetic run: 4 files / 8 KB); it is not free disk space and excludes source ZIPs, thumbnails, index, and temporary processing needs. A user cannot tell whether an import will fit, or whether deleting an archive would actually free space on the destination volume.

Proposed contract:

- Before Start, show the destination volume, available space, estimated additional peak space, expected final library size, and safety reserve. Separately show source ZIP storage and the space recoverable on **each** volume. Label uncertain estimates; compressed ZIP size is not extracted size, and re-encoding output size is not exactly known in advance.
- Estimate extraction from validated archive entry sizes; budget temporary metadata rewrites, simultaneous originals/derivatives, thumbnail growth, and worker concurrency. Check free space throughout the run and stop safely before exhausting the reserve. Another app can consume space after preflight.
- Offer a default-off mode named **“Delete each source ZIP after its contents are safely imported”**. Preview exact archives and bytes. State plainly that permanent deletion cannot be undone, explain the backup tradeoff, and retain archives on unsupported/skipped/conflicting/unverified contents or any failure.
- Process one archive at a time (or a bounded window), commit and verify outputs and an import manifest durably, then make that archive eligible for deletion. Do not delete based solely on file existence or the pipeline's current success count. Preserve non-media export data such as history JSON that later archives or a rerun may need, and do not drop unmatched entries silently.
- Same source/destination directory must work without the existing “scan every ZIP and delete” behavior. Use the user's selected archive identity, not a folder sweep; detect replacement/changes since preflight. On different volumes, deleting a source may not help destination capacity.
- Trash is recoverable but may not reclaim free space until emptied; say so. Do not silently empty Trash. For a true low-space mode, explicitly distinguish permanent deletion from moving to Trash.
- Persist per-archive progress for crash recovery, retain the currently incomplete archive, and expose what was deleted versus retained. If even one archive's peak does not fit, ask for another destination or external drive; do not suggest deleting that archive early.

Required failing tests: insufficient space at preflight and mid-write; source/destination on same versus different volumes; inaccurate/malicious entry sizes; interrupted verification; metadata needed by later archives; duplicate-name conflicts; unsupported entries; changed source archive; failed deletion; crash before/after commit; and default cleanup **off**. UI tests must assert units, estimate labels, actual affected paths, and truthful recovery text.

### D21 — Medium: native macOS window contract breaks AeroSpace expectations (user report, likely mechanism identified)

User reports SnapVault floats over other windows and does not tile normally in AeroSpace. `Main.kt` uses `undecorated = true`, custom right-side window controls, and manual maximize placement; `DraggableArea.kt` moves the AWT window manually. No `alwaysOnTop` setting was found. `WindowPlacement.Floating` is the normal non-maximized Compose placement; its name alone is not evidence of a topmost window level.

The packaged app has bundle ID `com.najdev.snapvault` and the accessibility tool identifies a standard window, but exposes no native fullscreen/minimize/close controls—only custom text glyphs. AeroSpace documents that windows lacking a fullscreen button are generally classified as dialogs and floated. This makes the missing native fullscreen control a strong explanation, **not yet an A/B-confirmed root cause**. [AeroSpace dialog heuristics](https://nikitabobko.github.io/AeroSpace/guide#dialog-heuristics).

Preferred fix candidate: on macOS retain a native decorated, resizable application window and native traffic-light/fullscreen controls, removing duplicate custom controls there. If a custom title bar is essential, preserve the native window capabilities and AX contract rather than replacing them with a borderless window. Validate stock macOS management, AeroSpace tiling/workspace switching, minimize/restore, Mission Control, fullscreen, dialogs and multi-monitor behavior. Keep Windows/Linux changes separately justified; neither is proven unaffected. Do not force “always on top” or change the user's AeroSpace configuration as the app fix.

Follow-up implementation must start with a failing macOS window-contract test (native title/fullscreen/resize capabilities) and a packaged-app AeroSpace reproduction, then verify the native-window variant resolves it. This pass records the candidate; no production window behavior has been changed.

### Additional live evidence

- Prior synthetic import's log: 4 files extracted, 3 metadata targets tagged, missing-history warning with date-only fallback, no duplicates, index saved, final clean success. Four expected media files and the index exist; source ZIP passes ZIP integrity checking and retains four entries. D10's clean-success despite a fallback warning is now observed live (fallback itself may be acceptable, but the summary should disclose it).
- **D11 reproduced in the actual app:** combining was disabled, yet the Library summary says “1 asset combined” and the main image inspector says “Combined”. Index shows `hasOverlay=true` for that uncombined main file. The Library counts the overlay as a separate memory: import says 3 memories, Library says 4 Memories / 4 photos. Define memory-versus-file counts and offer source-component filtering/grouping.
- Full image preview rendered the synthetic blue image. Close preview button worked. One Escape attempt did not close it; the tool/session also reported concurrent app-state changes later. Keep keyboard behavior under investigation rather than calling this a reliable reproduced app defect yet.
- Favoriting the synthetic main file from preview persisted `favorited=true` in `vault_index.json`. Favorites filter showed exactly that one file. Restart persistence and keyboard-only operation still to verify.

### D22 — High: navigating away silently resets processing choices, including destructive combination

**Observed live and confirmed in source.** Combination was disabled for the first synthetic run. After navigating through Library and returning to Dashboard, the wrong-ZIP run unexpectedly entered combination and logged `Found 0 overlay pairs. Combining…`. `DashboardScreen` holds `val options = remember { PipelineOptions() }`; leaving the screen removes that composition, so returning creates defaults again. `DEFAULT_RUN_COMBINE=true` restores the step that deletes originals; metadata/GPS choices likewise reset. This is especially surprising when visiting Settings to resolve a dependency warning before starting. The second run used an empty output folder, so it had no pairs or user files to delete.

Move user choices into app/view-model state that survives navigation and responsive-root changes. Distinguish the active run's immutable settings from next-run settings. Required regression: disable combination and GPS, navigate to Settings/Library and back (and cross Compact/Expanded roots), then assert both controls and the arguments sent to Start retain those choices. Test defaults separately; preserving settings is not a reason to weaken the safe defaults. Place this alongside D01–D03/D09 in the pre-promotion blockers.

### Wrong-input and remaining walkthrough results

- **D10 reproduced live with wrong input:** selected `/private/tmp/snapvault-ui-audit-20260915/wrong-export.zip` containing only `notes.txt`, with isolated `empty-output/`. App progressed through metadata/combination/dedupe, saved `{}` as its index, and reported **100% / Pipeline Complete / Done — 0 memories, 0 files on disk / Sync complete!** It should instead explain that the ZIP contains no supported memories and avoid processing unrelated output-folder contents.
- Selecting a different source and destination after a completed run left the previous 100% / Pipeline Complete state visible until Start. Tie the result panel to its completed run's explicit source/destination, or reset it to setup state when those inputs change; otherwise success visually appears to describe newly selected inputs.
- Favorite filtering worked and its value was verified on disk. Search text injection and repeated actions were unreliable through computer use: several calls reported that the user changed the app, even after state refresh; some keyboard/pointer actions produced no visible change while native file-picker keyboard navigation worked. These are **not** sufficient evidence to claim broken search, Escape, or tab navigation. Manual focused-app/VoiceOver testing remains required.
- Some screenshots showed a large blank white lower region while AX still exposed controls there. No controlled resize reproduction was completed; distinguish capture/window-manager interaction from a rendering defect before filing it as confirmed. AeroSpace A/B validation is the next useful window test.
- No production changes, new active tests, personal-file deletion, release publication, or AeroSpace configuration changes were made in this pass. Earlier passing desktop tests remain the baseline; documentation-only additions do not require rerunning the build.

### Latest continuation state (supersedes paused Pass 4 state)

Packaged app is open on Dashboard with logs expanded after the wrong-ZIP run. Selected input: `wrong-export.zip`; output: `/private/tmp/snapvault-ui-audit-20260915/empty-output`. Run finished; output contains only an empty index. Original synthetic library remains under sibling `output/`, including a saved favorite on `2024-01-02_AAA-main.png`. Both synthetic source ZIPs are retained. Inputs/settings are test selections only; no user preferences were deliberately changed.

Next priorities: implement D22 and other destructive-default/scope fixes under failing-test-first policy; validate native decorated macOS window against AeroSpace; complete keyboard/resize/restart walkthrough with reliable foreground input; implement the D20 space planner before optional verified per-archive cleanup. Remaining clean installer and cross-platform validation limitations still apply. User's 90% usage-threshold pause preference remains in effect; this session has no live usage-meter access.

## Pass 6 checkpoint — 2026-09-16, paused again at user's usage limit

User explicitly requested an immediate stop and information dump. No further testing should run until the user resumes. This section supersedes the prior continuation state.

### New checks completed

- Rechecked working tree: only untracked `docs/audits/`; production code unchanged. Reread project working notes and audit checkpoint.
- Inspected existing desktop XML test results to confirm the previous count: **208 tests in 34 classes** includes common pipeline/parser tests and Compose UI tests, including 14 `LibraryKeyboardTest` cases. This was inspection of previous results, **not a new suite run**.
- Closed the idle packaged app through its custom close control. Computer use reported `App quit`. Reopened the same built `.app` successfully.
- **Restart behavior:** source and destination selections were both unset after reopening. The app does not automatically reopen the prior library. This is a usability observation: favorites/media were not lost, but users must reselect their library directory each launch. Consider remembering the last library, with clear unavailable/moved-drive handling. No new finding ID assigned yet.
- Reselected `/private/tmp/snapvault-ui-audit-20260915/output` via the native destination picker, opened Library, then clicked Favorites.
- **Favorite persistence verified across a real app restart:** Favorites displayed exactly `2024-01-02_AAA-main`. This closes the earlier restart-persistence gap for an ordinary completed favorite write. It does not test quitting while a save is pending.
- Screenshot showed the full-height Library with no blank white lower area. The earlier white-region capture was not stable across restart; do not classify it as a confirmed rendering bug.
- Attempted native edge resize by dragging the visible bottom-right edge from `[1197,766]` to `[760,620]` in app screenshot coordinates. Neither AX state nor screenshot changed. This is a **failed automation attempt**, not conclusive proof resizing is broken. No layout/size change was verified. User interrupted immediately afterward.

### Exact current state

Packaged SnapVault remains open, idle, on **Library → Favorites**. It shows one saved favorite from the synthetic library at `/private/tmp/snapvault-ui-audit-20260915/output`. Source import selection is unset following restart. Default window screenshot is approximately 1199×768 pixels. No active import, subprocess test, or Gradle command was started in this pass. No preferences, production source, personal files, or AeroSpace config were changed.

Attach using:

`/Users/najchris11/GitHub/snapchat-memories-downloader/composeApp/build/compose/binaries/main-release/app/SnapVault.app`

Raw Java `MainKt` attachment previously failed; packaged app attachment works. Always obtain fresh AX IDs; IDs change when switching screens or refreshing full state. Computer-use keyboard/search and edge-drag attempts have been unreliable, whereas accessibility button clicks and native file-picker navigation have worked. Do not promote unreliable automation attempts into confirmed app defects.

### Remaining work to finish the audit report

1. Complete or explicitly close out unsupported live checks: search, sorting, keyboard arrows/Enter/Escape/tab focus, reveal in Finder, compact/medium resizing, pending-save close and cancellation. Use only existing synthetic fixtures. No more destructive default runs against the populated synthetic library without checking D22 (navigation resets options).
2. Consolidate D01–D22 into a concise severity/evidence/release-gate table near the top. Separate observed defects, source-supported risks, product requirements (disk-space planner/optional ZIP cleanup), and environment-dependent validation gaps.
3. Mark ordinary idle close/relaunch and completed-favorite restart persistence as passed. Keep pending-save shutdown, AeroSpace native-window A/B, screen reader, and actual resized layouts unverified.
4. Document local app-bundle launch separately from clean installer validation. DMG installation, Windows/Linux installers, minimum OS/architecture support, signing, real hardware encoder quality, native/dependency vulnerability inventory, low-disk and power-loss tests are not completed. If unavailable locally, close the audit with these as explicit release acceptance gates rather than silently assuming success or leaving an endless audit.
5. Finalize a prioritized remediation sequence with tests required by AGENTS.md; production fixes were not part of these audit passes. Do not claim any findings fixed.

User's 90% five-hour usage preference remains active. No live usage-meter reading is available to the agent. Paused immediately on the user's notification.
