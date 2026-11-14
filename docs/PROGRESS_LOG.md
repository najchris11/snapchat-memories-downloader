# Implementation Progress Log (2026-11-06)

Related documents:
- Full audit report: [`docs/audits/product-growth-audit-2026-11-06.md`](./audits/product-growth-audit-2026-11-06.md)
- Master checklist: [`docs/GROWTH_CHECKLIST.md`](./GROWTH_CHECKLIST.md)

---

## Completed in Iteration 1 (Checklist Section 1: Items `1.1` – `1.6`)

### 1.1 Per-OS Binary Bundling Hygiene (~35–80 MB Smaller Installers)
- **Changes:**
  - [`composeApp/build.gradle.kts`](../composeApp/build.gradle.kts): Added `resolveBundleTargetPlatform()` on `desktopProcessResources` so packaging tasks (`-PbundleTargetPlatform=<platform>` or distribution task names) exclude foreign `bin/<other-platform>/**` archives while `:composeApp:desktopTest` retains all committed `bin/**` bundles for `ShippedToolBundlesTest` and `ShippedToolManifestTest`.
  - [`.github/workflows/release.yml`](../.github/workflows/release.yml): Added `bin_platform` (`darwin-arm64`, `darwin-x64`, `windows-x64`, `linux-x64`) to the release matrix and passed `-PbundleTargetPlatform=${{ matrix.bin_platform }}` exclusively on `packageReleaseDistributionForCurrentOS`.
- **Tests:** `NativeDistributionConfigTest` (`composeApp/src/desktopTest/.../NativeDistributionConfigTest.kt`) and `scripts/test_release_workflow.py`.

### 1.2 Windows, macOS & Linux Native Installer Metadata
- **Changes:**
  - [`composeApp/build.gradle.kts`](../composeApp/build.gradle.kts): Added `description`, `vendor`, `copyright`, `licenseFile`, `macOS.bundleID = "com.najdev.snapvault"`, Windows deterministic `upgradeUuid = "8f4b2a9d-3e71-4c6a-9b18-52d0f7e3c4a1"`, `perUserInstall = true` (avoids unnecessary UAC admin prompts), `menuGroup`, `shortcut = true`, `dirChooser = true`, and Linux `debMaintainer`, `menuGroup`, and `appCategory = "AudioVideo"`.
- **Tests:** `NativeDistributionConfigTest`.

### 1.3 Automated Rich GitHub Release Notes (`body: null` Fix)
- **Changes:**
  - [`scripts/release_notes.py`](../scripts/release_notes.py): Generates structured markdown release notes with OS/CPU asset selection table (`macos-arm64` vs `macos-x64`, `.msi`, `.deb`), calm macOS Sequoia (`Privacy & Security → Open Anyway`) and Windows SmartScreen guidance, `SHA256SUMS.txt` verification snippets, and categorized commit changelog since the prior `v*` tag.
  - [`.github/workflows/release.yml`](../.github/workflows/release.yml): Generates notes into `$RUNNER_TEMP/RELEASE_NOTES.md` (outside `dist/` so `dist/*` upload stays clean) and passes `--notes-file` to `gh release create`.
- **Tests:** `scripts/test_release_notes.py` and `scripts/test_release_workflow.py`.

### 1.4 Shared JVM `ZipReader`, Unified Export ZIP Sorter, & Android `MediaScanner` (Closes #52)
- **Changes:**
  - Moved `ZipReader.kt` from `desktopMain` into [`composeApp/src/jvmSharedMain/kotlin/com/najdev/snapvault/ZipReader.kt`](../composeApp/src/jvmSharedMain/kotlin/com/najdev/snapvault/ZipReader.kt) and removed the empty `androidMain` stub so Android ZIP imports list entries, read `memories_history.json`, and extract `0x5455` `UT` timestamps identically to Desktop.
  - Added [`ExportZipSorter.kt`](../composeApp/src/commonMain/kotlin/com/najdev/snapvault/downloader/ExportZipSorter.kt) in `commonMain` and wired `DesktopZipPipelineRunner`, `AndroidZipPipelineRunner`, and `IosZipPipelineRunner` to share numeric multi-part sorting (`-2.zip` before `-10.zip`) and non-`mydata~` archive discovery.
  - Added [`JvmMediaScanner.kt`](../composeApp/src/jvmSharedMain/kotlin/com/najdev/snapvault/JvmMediaScanner.kt) (`minSdk = 24`-safe, zero `java.time.*` dependencies, UTC-noon calendar formatting so `YYYY-MM-DD_` prefixes never shift backward west of UTC) and implemented [`androidMain/MediaScanner.kt`](../composeApp/src/androidMain/kotlin/com/najdev/snapvault/MediaScanner.kt) with downsampled `BitmapFactory` image thumbnails and `MediaMetadataRetriever` video frame thumbnails. Updated `iosMain/MediaScanner.kt` to use `SupportedMediaExtensions.ALL` and `SupportedMediaExtensions.VIDEO`.
- **Tests:** `ExportZipSorterTest` (`commonTest`), `JvmSharedAndroidParityTest` (`desktopTest`), and existing `MediaScannerTest` + `ZipReaderTimestampTest`.

### 1.5 Android Staged Overlay Combine, Atomic Non-Overwriting Publish, & Date Fallback (Closes #51 & #64)
- **Changes:**
  - Added [`StagedImageOverlayCombiner.kt`](../composeApp/src/jvmSharedMain/kotlin/com/najdev/snapvault/downloader/StagedImageOverlayCombiner.kt) in `jvmSharedMain` and wired [`AndroidZipPipelineRunner.kt`](../composeApp/src/androidMain/kotlin/com/najdev/snapvault/downloader/AndroidZipPipelineRunner.kt) with kernel-atomic `Os.link` non-overwriting publish (`minSdk 24` compatible, with synchronized fallback for FAT/FUSE mounts), `METADATA_NOT_CARRIED_MARKER` gating before deleting originals, and `onMetaStart` / `onMetaError` filename-date fallback batching.
  - Updated [`AndroidMediaProcessor.kt`](../composeApp/src/androidMain/kotlin/com/najdev/snapvault/metadata/AndroidMediaProcessor.kt) so `.png` base images encode as `Bitmap.CompressFormat.PNG` (preserving alpha transparency) rather than writing JPEG bytes into `.png` outputs.
- **Tests:** `JvmSharedAndroidParityTest` (`desktopTest`) and `OverlayCombineOutcomeTest` (`commonTest`).

### 1.6 Documentation, Privacy Policy & Contributor Onboarding
- **Changes:**
  - Updated [`README.md`](../README.md) with website/tutorial/privacy links and macOS Sequoia 15+ (`System Settings → Privacy & Security → Open Anyway`) instructions.
  - Updated stale notes in [`CLAUDE.md`](../CLAUDE.md) (`StringResourceHygieneTest` enforcement and `iosMain` `VideoPlayer` `remember` state).
  - Added [`PRIVACY.md`](../PRIVACY.md) (App Store / Play Store ready zero-telemetry disclosure) and [`CONTRIBUTING.md`](../CONTRIBUTING.md).

---

## Next Steps for Iteration 2 (Resume Point)

Pick up directly at **Section 2** of [`docs/GROWTH_CHECKLIST.md`](./GROWTH_CHECKLIST.md):
1. **Item 2.1 — "Keep original unmerged files" toggle (`DashboardScreen`, `DashboardViewModel`, `VaultIndex`):**
   - Add `keepUnmergedOriginals: Boolean = false` preference (with named default constant) under *Combine captions & stickers* so power users can retain `-main` and `-overlay` files alongside merged composites.
2. **Item 2.2 — Unified / auto-detecting export source picker (Issue #54):**
   - Collapse the multi-mode export picker friction by auto-detecting whether a selected path is a folder of `.zip` archives, a single `.zip`, an extracted Snapchat export directory, or a legacy `memories_history.json`/`.html` file.
3. **Item 2.3 — Opt-in manual "Check for updates" button (`SettingsScreen`):**
   - User-initiated check against `https://api.github.com/repos/najchris11/snapchat-memories-downloader/releases/latest` comparing semver against `AppBuildConfig.VERSION` (zero automatic launch pings).
4. **Section 3 (`snapvault-web` repository):**
   - Add `robots.ts` + `sitemap.ts`, fix `/tutorial` anchor nav (`/#...`) + mobile drawer, wire direct OS/CPU installer download buttons with Gatekeeper/SmartScreen helper modal, and replace CSS hero mockups with current app screenshots.


## Completed in Iteration 2 (Checklist Section 2: Items `2.1` – `2.3`)

### 2.1 Keep original unmerged files
- Added `DEFAULT_KEEP_ORIGINALS = false` and view-model-owned option state. The nested Dashboard switch is visible only when combining and disabled during runs; its value is captured at Start and passed to both ZIP and legacy pipelines as `deleteOriginals = !keepOriginals`.
- Disclosure distinguishes retained originals from permanent cleanup and explains that duplicate cleanup remains separately controlled.
- Existing `VaultIndex.writeMerging` / combined-output recording already retain entries for sources still on disk. Regression coverage now confirms both originals and combined output retain accurate badges and favorites; existing deleted-source and failed-metadata tests remain green.
- Tests: `DashboardScreenTest`, `DashboardViewModelTest`, and fresh defaults in `PipelineOptionsNavigationTest`. The new UI test failed on the absent control; deliberately restoring hardcoded deletion also failed the runner/index test before restoring the fix.

### 2.2 Auto-detect export sources (Issue #54)
- Replaced Dashboard mode selection with export file / folder actions and retained multi-ZIP selection. Shared `detectExportSource` recognizes ZIP files, ZIP folders, direct JSON/HTML history files, and extracted export roots with history at root or in `json/` / `html/`. ZIPs take precedence in mixed folders.
- Detection configures existing pipeline modes and reports the detected format/path. History feedback explicitly says media will be downloaded from Snapchat. Unsupported or unreadable sources retain the previous selection and show an error.
- Desktop and iOS file pickers accept ZIP/JSON/HTML. Android's existing cache-copy file picker preserves the selected filename/extension. Android SAF directory access remains the separate 2.4 roadmap item; unsupported `content://` directories fail visibly rather than being treated as filesystem paths.
- Tests: `ExportSourceTest`, ViewModel source-selection regression, and Dashboard picker/feedback UI test. Existing navigation/run-control UI tests now select history through the unified picker while preserving their behavioral assertions. Deliberately breaking extracted-history recognition and the UI format feedback failed the corresponding tests, then both were restored.

### 2.3 Manual update check
- Added `ReleaseUpdateChecker` and a Settings action that calls GitHub's `releases/latest` endpoint only on click. Client creation occurs inside the request, with a bounded timeout and client cleanup. Numeric version comparison uses `AppBuildConfig.VERSION` by default; invalid responses, HTTP errors, drafts and prereleases produce a retryable error.
- Settings shows checking/current/available/failure outcomes, disables repeat clicks while checking, and links available updates to the official release downloads. No automatic requests or installation. `PRIVACY.md` documents this optional network access.
- Tests: `ReleaseUpdateCheckerTest` and `SettingsUpdateTest`, covering numeric comparison, equal/older releases, malformed responses/versions, HTTP failures, prereleases, no request on Settings launch, and click/retry results. Deliberately introducing a startup request and an always-newer comparison failed the tests; both were restored.

### Verification
- Python suite: **42 passed**.
- Desktop suite: **505 passed**, zero failures/errors/skips.
- `detekt`: passed; no baseline changes.
- Desktop, Android (`ANDROID_HOME=/opt/android-sdk`), and iOS simulator compilation: passed on Linux.
- Jev final Section 2 diff check: **PASS** (three requirements judged). Router HALT and unavailable external plan review were explicitly waived by the owner; strict TDD and final verification were retained. No paid model review requested or run.
- Iteration 1 changes remain uncommitted and preserved alongside this iteration.


## Completed in Iteration 3 (autonomous continuation)

### Desktop media features
- **Lossless video stitching:** Library action with explicit clip ordering and a recoverable-error flow. FFprobe validates stream codec/profile/timebase/extradata/audio/geometry compatibility; FFmpeg uses stream copy, retains every original, checks resulting duration, and publishes a complete new file without overwriting via a hard link. The output directory is locked during writing; cancellation kills the child process and cleans only its unique staging directory. FFmpeg and FFprobe must be available; incompatible clips and unsupported output filesystems fail safely. No automatic grouping of unrelated memories. Native mobile stitching is explicitly unavailable.
- **Offline GPS timezones:** pinned desktop Timeshape `2026b.29`, lazy boundary index, unique-zone lookup, Java IANA historical offset. Photo EXIF capture/create/modify times receive matching local times and offsets; filesystem timestamps and existing video timestamp behavior remain UTC. Incomplete dates, invalid coordinates, unknown zones and ambiguous locations yield no inferred offset. Date-only rewrites clear stale offsets. Dataset/code license texts and the approximately 22 MiB database size are disclosed. Native mobile timezone writing is still pending.
- **Offline Memories map:** bundled public-domain Natural Earth land geometry, pan/zoom, tap selection and an accessible memory list. Verified GPS writes persist nullable coordinates into the index; scanners carry them to Library, and combined outputs inherit them only when metadata was carried. Older indexes remain readable; GPS badges without coordinates do not invent a map location. No tile downloads or coordinate requests.

### Disk-space planning and cleanup safeguards
- Added a click-triggered, read-only extraction/processing-reserve/free-space estimate before a run. Unsupported or unselected sources do not produce a misleading estimate. The run still measures free space again.
- Strengthened JVM archive verification from presence/size to streaming SHA-256. Unrecognized members or duplicate names prevent deletion. Export history must have a content-verified backup; conflicting old backups are never overwritten. Existing low-space tests execute actual extraction/deletion only in explicit test opt-in mode.
- **Production activation approved and completed:** automatic approval review initially required explicit owner authorization. The owner subsequently approved the prepared confirmation-only activation patch. `LOW_SPACE_DELETE_ENABLED` is now true; the default regression proves that a normal run only presents the offer and leaves ZIPs untouched until explicit in-app confirmation. The reviewed patch is retained under `docs/approvals/` as an applied review artifact.

### Companion website (Section 3)
Local checkout: `~/Documents/GitHub/snapvault-web` (was clean before this iteration).
- Added robots/sitemap, correct canonicals including guide/tutorial routes, keyword-focused title and large social sharing card (1200×630 PNG, approximately 57 KiB).
- Fixed tutorial navigation to home-section URLs; added a keyboard-native mobile menu with close-on-navigation behavior.
- Added latest-version installer resolution at `/download/[target]`, validating official GitHub asset URLs and falling back to Releases on rate limits, errors or missing assets. Explicit Apple Silicon/Intel choice avoids unreliable browser architecture guessing. Post-click native dialogs explain unsigned-installer steps and checksums. Linux documentation includes Perl.
- Added phone export-request, email and Web Share/copy handoff without sending messages automatically.
- Replaced the mock UI with actual current Compose Dashboard/Library captures. Library imagery is generated demo data. An actual compositor/ExifTool run created the before/after GIF and verified date, offset and GPS tags on its combined JPEG; the public metadata proof omits the temporary build path.
- Added website-only Vercel analytics with Do Not Track handling and query/fragment redaction, software/visible-FAQ/video structured data, GPL license and four substantive guide/comparison pages linked from the footer and sitemap. The tutorial's unverified upload date is intentionally omitted.
- No site deployment or live repository/settings changes were made. Vercel analytics dashboard activation and Search Console/domain work remain owner/external actions.

### Growth assets
- Three silent, 18-second, 1080×1920 H.264/24fps vertical demo MP4s in `docs/growth-assets/`, with caption drafts, source policy link, generated-media disclosure and reproduction instructions.
- `scripts/render-growth-assets.sh` temporarily installs an artifact-only test exporter, uses the real compositor and portable bundled Perl ExifTool, verifies JPEG tags, exports the GIF, then removes its own temporary test source. Existing output artifacts are refused.
- `scripts/growth_clips.py` renders current-UI promotional drafts without overwriting outputs. These are new demo clips, rather than edits of the older walkthrough. Nothing has been posted or submitted.

### Verification and limits
- **525 desktop tests passed**, zero failures/errors/skips; **44 Python tests passed**.
- `detekt`, desktop/Android/iOS simulator compilation passed; **no baseline additions**. Android uses `ANDROID_HOME=/opt/android-sdk`.
- Companion site: **9 tests passed**, ESLint passed, TypeScript/Next.js production build passed. Built HTML was checked for correct home/tutorial/guide/comparison canonicals.
- Tests were written and observed failing before behavior changes. Where a new check initially passed against existing code, deliberate mutations proved sensitivity: forced deletion, broken source feedback/history detection, automatic update requests/version comparison, missing map coordinates, stale EXIF offsets, reversed stitched frame order, and a broken installer redirect were caught and restored. Real FFmpeg decoded-frame hashes verify clip order/content; the artifact exporter verified actual copied JPEG EXIF through ExifTool.
- Router and unavailable external plan-review gates remain waived by the owner from Iteration 2; final cheap requirements verification is retained, with no paid model review.
- Device inventory reported an unreachable mobile host and no local iOS simulator. Native Android SAF/MediaStore and iOS AVFoundation/Photos work remains pending real platform execution; no mobile completion is claimed.
- Prior uncommitted work is preserved. Both app and website changes remain uncommitted.

Final requirements checks: Jev passed the website and each app feature (stitching, timezone, map, disk planning/safeguards, growth assets). The combined app diff exceeded the verifier’s command-line argument limit, so it was checked in feature-sized diffs. No paid model review ran.


### Approved cleanup activation follow-up
- Applied the owner-approved production flag and default regression-test changes. Updated stale disabled-feature comments; retained all content verification, unpreserved-member/history backup refusal, disk-space and durable-manifest safeguards.
- TDD: applied the new default test first and observed it fail while the flag remained false; enabled the flag and confirmed it passes with the full suite.
- Validation: **525 desktop tests passed**, zero failures/errors/skips; `detekt` and desktop/Android/iOS simulator compilation passed. No user export cleanup, commits or deployment were performed.

## Iteration 4 continuation — Android document streaming and explicit gallery copies (2026-11-06)

- Replaced whole-archive picker cache copies and duplicate/no-op Android pickers with one native SAF file/tree implementation. Persisted read grants and provider display names feed native source auto-detection. Mixed unreadable multi-selections fail visibly without replacing the previous selection.
- Added seekable document central-directory inspection and a streaming fallback, bounded history reads (64 MiB), and single-pass ZIP extraction into staged, non-overwriting library files. Source documents remain read-only, and document URIs never qualify for source archive deletion offers.
- Dashboard, Settings and empty Library now disclose the fixed app-owned working library, its uninstall lifecycle and the separate gallery-copy action.
- Added explicit Android 10+ MediaStore publishing in media preview. Each operation creates its own pending row, copies bytes, commits only after success, and rolls back its own row on failure/cancellation. The original library file stays unchanged. Gallery export is disabled during imports; no broad storage/media-reading permissions are requested. Desktop/iOS report this feature unsupported.
- TDD covered stream parsing/limits, extraction outcomes, native URI selection, UI disclosure, click-only gallery publication, rollback and cancellation before commit. Failure-first tests found and fixed an extraction staging error that omitted failure callbacks and a cancellation window immediately before gallery commit. Mutation checks prove history-limit and Settings-disclosure assertions fail when their guards are removed, then pass after restoration.
- Native validation remains pending: the remote host became reachable, but APK installation returned `DEVICE_IN_USE`, owned by an active CoffeeBuddy session. Following the supplied status advice returned that remote claims cannot be read through that command. Device work stopped; no other session was reused/closed and no runtime success is claimed. The isolated test APK uses `com.najdev.snapvault.roadmaptest` so eventual testing need not affect the existing app's data.
- Final validation: **539 desktop tests passed**, zero failures/errors/skips; `detekt` and desktop, Android and iOS simulator compilation passed. Final isolated Android APK assembly passed. No detekt baseline entries added; `git diff --check` clean.
- Final read-only requirements review passed for streaming input, native pickers and gallery publication. The first gallery review omitted the Library integration diff; including that integration resolved the reported coverage gap. This review does not replace pending native runtime QA.

## Iteration 5 — iOS add-only Photos publication (2026-11-06)

- Added explicit **Save to Apple Photos** with add-only authorization, file-URL image/video creation requests, completion-based success feedback and retryable failure. Embedded source metadata is supplied through the file; the working-library original is never removed. Added the required add-only usage description to the app plist. No launch-time permission or publication work is introduced.
- Common boundary tests cover permission denial (no creation), missing/unsupported sources (no prompt), successful image/video submission ordering and transaction failure. Compose coverage proves the Photos action waits for a click and reports success. Both slices were observed red before implementation.
- iOS native bindings compile; native permission/Photos runtime QA remains pending. Once submitted, a system Photos transaction can finish even if its initiating preview closes; documentation states this limitation. AVFoundation overlay burn-in is still open.
- GitHub owner actions are blocked by an invalid saved token (`gh auth status`); no authenticated changes were attempted. No account registration or public posting was performed.
- Full verification: **543 desktop tests passed**, no failures/errors/skips; detekt and desktop/Android/iOS simulator compilation passed. Final requirements review passed; `git diff --check` clean.

## Iteration 6 — iOS ZIP video overlay exporter (2026-11-06)

- Added capability-aware video dispatch, retaining the default skip for unsupported platforms and existing-output precedence. iOS ZIP imports call a suspend AVFoundation exporter directly; the synchronous legacy MediaProcessor interface remains unsupported for video.
- The exporter overlays the PNG through Core Animation using a source-derived video composition and the original audio-bearing asset. It forwards asset metadata, exports to a unique staging file, cancels/drains the encoder before cleanup and refuses an existing final output. Both original video and overlay are retained; metadata fidelity is explicitly unverified and the output does not inherit GPS claims.
- Failure-first common tests cover capable/unsupported dispatch, conflicts, successful separate output, encoder failure and cancellation cleanup. iOS compilation exposed a readonly inherited `animationTool` property twice; platform klib metadata confirmed the separate `setAnimationTool` selector, now used directly. No baseline additions.
- Native validation remains pending. The iOS device panel connects, but this Linux checkout has no Xcode or updated runnable iOS app artifact and the device-control API supplies no build operation. Source cross-compilation does not prove pixel layout, orientation, audio or Photos authorization. These remain unchecked in the roadmap.
- Additional mutation proof: the concurrent-destination test fails when publication refusal is ignored; the missing-source test fails when input validation is removed. Both production guards were restored. Native overlay contents use an explicit retained Core Foundation image bridge for CALayer.

## Iteration 7 — canonical-host redirect preparation (2026-11-06)

- Added a host-specific permanent redirect for `snapvault-web-three.vercel.app` to `https://snapvault.naj-dev.com`, preserving matched paths. The canonical hostname does not match the rule, preventing a loop. Next.js emits 308 for permanent redirects and carries query strings forward ([official v15 reference](https://nextjs.org/docs/15/app/api-reference/config/next-config-js/redirects)).
- A new Node regression test failed on the missing redirect function before implementation and passes with the rule. Deployment, live HTTP validation, Search Console/Bing verification and GitHub homepage changes remain pending external access.
- Verification found a weak missing-source test: throwing inside the encoder could be caught and return failure even when the encoder ran. Changed the assertion to observe invocation count, then repeated the mutation proof. The output-conflict test had already failed correctly.
- Canonical redirect validation: 10 website tests and ESLint passed, production Next.js build passed, and generated `routes-manifest.json` contains the host predicate, canonical destination and `statusCode: 308`. Read-only final requirements review passed. No deployment performed.
- Final audit caught unsupported AVI/MKV output names. Added a failure-first container eligibility test and restricted native dispatch/export to MP4/MOV/M4V so MPEG-4 bytes cannot be published under an incompatible extension.
- Final post-audit verification: **549 desktop tests passed**, no failures/errors/skips; `detekt`, desktop/Android/iOS simulator compilation passed; Python suite **44/44** passed. The final video requirements review passed. Both checkout diffs are whitespace-clean and the detekt baseline is unchanged. Full implementation/access audit: `docs/audits/checklist-execution-status-2026-11-06.md`. Native QA and authenticated owner actions remain incomplete, so the entire roadmap is not claimed finished.

## Final access audit (2026-11-06)

Historical access state: the 2026-11-08 GitHub growth settings section below supersedes the invalid-token blocker, and the native deployment sections supersede the Mac/device outage.

- Revalidated remaining unchecked items and successful final build logs. No additional local feature implementation remains in the enumerated checklist; native QA and owner/external actions remain required.
- Corrected the GitHub blocker: the CLI token is invalid, but the connected GitHub app authenticates as the owner and reports repository admin access. Its available tools do not expose repository settings/topics or release updates, so they cannot perform Section 4.1 or the website homepage change. No connector credentials were extracted or reused outside the connector.
- No Vercel/search-console connection or local Xcode build tool is available. Android device work remains stopped following the prior ownership conflict and failed prescribed follow-up; no other session was touched. These persistent blockers prevent completing the full checklist autonomously.

## Native deployment resume attempt (2026-11-08)

- Owner requested resumed device deployment with the MacBook expected online. Revalidated through device tools: `device_list` reports the Mac host failed with “Could not connect to this host over SSH.” A single direct `device_open` retry returned “Christian’s MacBook Pro is unavailable: Could not connect to this host over SSH.” Device operations stopped after that retry; no stale inventory or previous device ownership was treated as evidence of current availability.
- No app installation, launch, simulator takeover or native QA occurred. Native checklist entries remain unchecked. Local Android test APK preparation continues independently; the Mac's T3 device-host SSH connection must be restored before deployment can resume.
- Refreshed isolated Android APK assembly passed; `aapt2` confirms package `com.najdev.snapvault.roadmaptest`. No production application ID was changed. `git diff --check` passed.

## Native deployment, crash diagnosis and repair (2026-11-08)

- The Mac reboot restored SSH and Device Hub. Installed isolated packages `com.najdev.snapvault.roadmaptest` and `com.najdev.snapvault.roadmaptest.ios`; existing production apps and other workspace sessions were left untouched. Xcode 27.2 simulator build passed with `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES` (the generic default also requested unsupported x86_64).
- Android native smoke: SAF selected a synthetic ZIP, detected it as an archive, imported into the disclosed app-owned library, combined a photo, retained the unsupported video pair, and listed the results. Explicit image and video gallery saves reported success; MediaStore queries show both new rows with `is_pending=0`. Source ZIP SHA-256 remained `72e25f7318831831117916e6e5ee267777d2dabf7761b00967f3bb05b2ca6f3a`. No personal exports used. The test fixture is a two-second H.264/AAC clip, JPEG and transparent PNG overlays.
- Native smoke found the stale Android “importing does not work” banner. Added a failing Compose assertion, then updated resource copy to describe ZIP/photo overlay support, retained separate video overlays and gallery copies.
- First iOS UI import quit during video combine. Crash report `iosApp-2026-11-08-152908.ips`: EXC_BREAKPOINT/SIGTRAP, `_xpc_api_misuse` → IOSurface → QuartzCore → `FigVCPCoreAnimationPostProcessorCopyPixelBufferAtTime`. Both source files survived; process termination left its unique stage. No prior runtime pass was claimed.
- Added actual `iosTest` coverage and deterministic media fixtures with simulator resource environment wiring. The native exporter test terminated before the fix. Replaced Core Animation postprocessing with AVVideoComposition Core Image source-over filtering in AVAssetExportSession, retaining staging, cancellation drain, no-overwrite and source-retention behavior. Native test now passes and verifies exported video/audio tracks plus retained sources.
- Rebuilt/reinstalled the isolated iOS app and reran the same ordinary UI import successfully. Library displayed the new combined video/photo. Independent FFprobe/FFmpeg checks of the actual UI output verified H.264 120×160 video, AAC audio and the red overlay at the top (sample RGB mean 244.665/21.28/5.03). Source ZIP hash remained unchanged. This validates the fixture orientation/layout, not every rotation/HDR/provider combination.
- iOS video “Save to Apple Photos” showed the add-only prompt on click, completed after Allow, displayed “Saved to Apple Photos” and disabled the repeat-save button. The app remained running.
- Local post-fix checks: 549 desktop tests, detekt and desktop/Android/iOS compilation passed; the real Mac simulator exporter regression passed. Requirement review passed and whitespace checks are clean. Physical-device, rotated/HDR video, large/non-seekable cloud providers and permission-denial/cancellation runtime matrices remain pending; common/JVM tests cover failure/cancellation logic.
- iOS image publication also passed through the actual preview: “Saved to Apple Photos” appeared and repeat save was disabled. Both photo and video branches are now exercised natively.
- The final Android banner-only APK update initially hit a Device Hub request timeout. Package inspection showed the previous tested version still installed, and the timed-out installer process was confirmed stopped before a single documented `reinstall --debug` retry. This tooling issue does not invalidate the already completed SAF/MediaStore smoke; final deployment status is recorded separately.
- The documented Android reinstall retry succeeded. Both final isolated test apps now contain the checked source changes; no production app was replaced.
- Android reinstall reset only the isolated test app's onboarding/working state. Both previously saved synthetic gallery copies remained published (`is_pending=0`), directly verifying the disclosed separation from app-owned storage. No production app data was touched. Final whitespace check passed.

## Native matrix continuation (2026-11-08)

- Verified actual Photos denial on the isolated iOS app: reset only its permissions, clicked Save, chose Don't Allow, and observed retryable failure feedback without app termination. Reset again and exercised the normal permission prompt to return it to a usable state.
- Added a second real native exporter case for 90° display rotation and explicit portrait/landscape encoded dimensions. The first generated fixture was invalid: the old FFmpeg `-metadata rotate=90` syntax wrote no matrix. FFprobe caught this; the corrected `-display_rotation:v:0 90` fixture reports rotation90. No production orientation change was needed. A temporary async-composition experiment was reverted; production remains the prior working Core Image renderer.
- Proved the corrected native test is sensitive: temporarily removing `videoComposition` in the isolated Mac checkout makes the rotated case fail; restoring production makes both native cases pass. Native test resources are now declared Gradle inputs, and outputs are available under build/native-test-artifacts for diagnosis.
- Standalone simulator exports produced green frames in this environment, unlike app-hosted export. Native tests therefore claim structural/crash/audio/source-retention and dimension coverage, not rendered-pixel coverage. This limitation is documented in the fixture README and test comments.
- Independently validated the ordinary UI rotated output: 160×120 H.264 with audio, no residual display matrix, and a red top overlay. The uncomposited source sample was cyan (RGB 0.425/254.99/254.335), while output top was red-dominant (241.005/75.005/77.005); the negative-control source fails the overlay assertion. Alpha/color-space blending means an absolute green/blue threshold of60 was too strict, so the assertion uses strong red dominance plus the source negative control. Originals were retained.
- Android SAF tree import passed for a dedicated folder containing mydata-2.zip and mydata-10.zip. Library contained the additional standalone photo as well as the first archive's outputs. After process relaunch, system grant inspection reported mode0x1, owned0x0 and persisted0x1 for only that tree, proving persisted read access with no transient activity owner. Direct provider reopen from a fresh process still needs instrumentation coverage; the grant itself is verified.
- No personal media, production app data, source archive deletion, public posts or release/deployment actions were used in these checks. The isolated app remains available for further cancellation/provider testing.
- Final native JUnit report for the expanded suite: 2 tests, 0 failures/errors/skips. Local required checks passed: 549 desktop tests, detekt, and desktop/Android/iOS compilation. No baseline changes; whitespace clean.
- Initial automated diff review incorrectly bundled runtime/mutation requirements into a source-only check and flagged the slice. The runtime proof was inspected in native logs/JUnit and UI output; the code-only requirements review passed (0.91). No production code was changed to satisfy the reviewer.
- After the denial test, the real prompt was allowed again and the verified rotated video reported “Saved to Apple Photos.” No isolated test app was left with denied Photos access.

## Native safety and fresh-process provider tests (2026-11-08)

- Expanded the real iOS exporter suite to four cases. Cancellation test waits until the native encoder has created a stage, confirms the job is still active, cancels/joins it, verifies only the source video/overlay remain and checks no late stage reappears. Output-conflict test supplies an existing file and verifies byte-for-byte preservation plus a failure result.
- Verified both new cases can fail: a temporary mutant in the dedicated Mac checkout deleted an existing output and removed the wrong file during cleanup. Both tests failed; production source was restored and all four native tests passed. The local repository's production renderer was not changed.
- Added Android instrumentation infrastructure and a fresh-process persisted-document suite. Test compilation first failed on the missing test dependencies, then the instrumentation APK built after AndroidX runner/JUnit wiring. The suite requires a real previously selected synthetic tree, reads through ContentResolver without an Activity/picker, checks read-only persistence and rejects a document outside the grant. Missing preconditions fail explicitly. Installation/native execution is still pending at this point; no Android instrumentation pass is claimed yet.
- Required local checks passed after the test/configuration changes: detekt, 549 desktop tests and desktop/Android/iOS compilation; baseline unchanged. Existing app data/grants were preserved while installing only the test package.

- Android native execution completed: both persisted-document instrumentation cases passed in a fresh process with only the real picker-granted read-only tree access. A deliberately broken document adapter made both fail; restoring and reinstalling the checked APK preserved the grant and returned **2/2 passing**. Actual runner assertions were checked, rather than trusting its shell exit status.
- The original Android emulator had only 290 MB free and could not install the instrumented build. Preserved its data and created a dedicated Android 36 QA AVD (`SnapVault_QA_t3_24e5c88e`, emulator-5556) with 8 GB storage. Only synthetic fixtures and isolated test packages were installed. No global cache wipe, production uninstall or other session takeover occurred.
- Final native safety source review passed (0.96). Required local lint/tests/all three target compilations passed, with 549 desktop tests; native iOS suite **4/4** and Android suite **2/2** passed after restoring all mutation changes.
- Followed up the owner's report that iosApp quit: current simulator snapshot shows the app running with the rotated video and “Saved to Apple Photos.” Mac process inspection confirms it is live. No newer iosApp crash report exists beyond the previously diagnosed 15:29 Core Animation crash. The rebuilt Core Image version remains installed; no additional crash fix is inferred from a historical report.

## Non-seekable Android provider validation (2026-11-08)

- Added an instrumentation-only ContentProvider that generates a ZIP through a real ParcelFileDescriptor pipe with an 8 KiB buffer and 16 MiB of uncompressed payload before the history document. Native test exercises the actual source descriptor, failed seekable-directory probe, production streaming fallback, repeated opening, history/missing-entry lookup and unchanged cache.
- Initial test harness failed because the provider runs in a separate test-APK process without the target Kotlin runtime. Targeted native logs identified NoClassDefFoundError for Kotlin Intrinsics. Replaced only the test provider with self-contained Java and added a 30-second timeout; no production fix was needed.
- TDD sensitivity proved by temporarily replacing the production streaming entry fallback with an empty list: expected native assertion failed. Restored source and app, then all **3 Android provider tests passed** together; persisted read-only tree access survived the update. No grant reset, user-data wipe or production package change occurred.
- Required detekt, full desktop suite and desktop/Android/iOS compilation passed. Cloud-service/multi-gigabyte and physical/HDR behavior remain separate unverified cases; a synthetic pipe is not evidence of those providers.

## GitHub growth settings completed (2026-11-08)

- Access changed: local `gh api user` now authenticates as `najchris11`; both repositories report admin permission. The connected app still lacks settings/release mutation tools, so the authenticated CLI handled these authorized checklist actions. The Mac CLI still returned HTTP 401; no credentials were copied between machines.
- Applied the exact requested application repository description, added all ten requested topics while preserving the prior four, and enabled Discussions. Updated the companion repository homepage to the canonical `https://snapvault.naj-dev.com`.
- Backfilled the previously null v1.0.15 release body with the tested release-note generator's installer selection, first-launch help, checksum instructions and actual v1.0.14→v1.0.15 commit list. Removed the future per-user installer wording from this historical backfill; no uncommitted feature is represented as shipped in v1.0.15.
- Independent API reads matched the requested description/Discussions/topics/homepage and exact release text. Every release asset ID, SHA-256 digest and byte size matched its pre-update state. No installers, tags, source commits, pull requests or deployment changed. Section4.1 is now complete; the GitHub-homepage part of4.2 is complete. Search Console/Bing, Vercel deployment, store and community publication remain pending.
- Native pipe source requirements review passed (0.95); production merged manifest contains no test provider, whitespace is clean, and 549 desktop tests report zero failures/errors/skips.

## Durable evidence and audit corrections (2026-11-08)

- Archived the real iOS four-case JUnit report, Android three-case restored runner output, failing pipe-mutant assertion, current source hashes at archival and required-check totals in `docs/audits/evidence/native-2026-11-08/`. The archive explicitly separates structural native tests from app-hosted pixel evidence and identifies its coverage limits.
- Corrected the audit summary table and mobile matrix to reflect successful native runs and completed GitHub actions; historical access failures remain dated and explicitly superseded. Remaining tests and external steps remain unchecked.
- Inspected the downloaded, checksum-matched published Windows MSI with msitools: v1.0.15 has ALLUSERS=1 (machine scope), ProductCode `{FDF695BB-26FD-3E6F-B3A1-6071E297B937}`, Manufacturer `Unknown` and x64 template. Removed the README's incorrect current-download per-user/no-administrator claim. Future per-user packaging is still unreleased and its upgrade path needs Windows validation.
- Claude's read-only checklist audit confirmed Vercel is Git-integrated: pushing the companion checkout to main would deploy publicly. No push was performed. Winget preparation and Android provider error/cancellation tests are delegated to Claude; optional synthetic ZIP64/HDR exploration is not substituted for the remaining real cloud/hardware requirements.

## Winget candidate preparation completed (2026-11-08)

- Claude prepared three candidate manifests and scoped validation/evidence under `distribution/winget/`. Parent independently reran the validator against the downloaded MSI: size/SHA-256, pinned hash-verified 1.12.0 and 1.28.0 schemas, cross-file and actual-MSI agreement all passed. Nine deliberately invalid variants were rejected, including valid-looking wrong hash, wrong ProductCode and the future per-user scope. Captured output is `distribution/winget/schema-validation.log`.
- Actual v1.0.15 WiX installer values are retained: machine scope, x64, manufacturer `Unknown` in the ARP entry, and the extracted product/upgrade codes. License and description are tied to the release tag. No future uncommitted features are advertised as released. Candidate identifier `NajDev.SnapVault` follows the existing product/vendor identity; it is not a registered or published package yet.
- A malformed on-disk hash first failed validation, then the exact manifest was restored and passed. The candidate validator's tests are explicit negative controls, not claimed as a separate failing-first unit suite. Existing Python suite remained 44/44 and dependency-free; schema-validator dependencies live only in its temporary validation environment.
- Native Windows validation, silent install/list/uninstall, sandbox and security scan checks remain unverified. No Windows host is available. The preparation checklist subitem is complete; package-manager publication remains unchecked.
- Archived independently read-back live GitHub settings and full release text plus unchanged asset IDs/checksums/sizes in `docs/audits/evidence/github-growth-2026-11-08.json`. This verifies settings/notes only, not a new release or deployment.

## Release-specific launch copy prepared (2026-11-08)

- Claude drafted directory fields, description and a reusable forum fact sheet in `docs/growth-assets/launch-drafts.md`, anchored to the published v1.0.15 README/assets. Parent checked the release facts and independently read Snapchat's current storage help and Hacker News guidelines. Replaced long policy quotations with accurate paraphrases and linked the primary sources.
- Copy distinguishes local ZIP processing from legacy link downloads, discloses unsigned installers and excludes unshipped roadmap features and undeployed guides. No testimonials, usage figures, listings or competitor feature claims were invented.
- HN prohibits generated/AI-edited posting text, so no HN title or comment was drafted. Automated Reddit rules requests returned 403; no subreddit-specific copy or submission was produced. Public posts, directory account/terms and actual submissions remain owner/external actions. No registration or publication occurred.

- Follow-up primary-source directory checks: AlternativeTo requires a verified email/account; OpenAlternative redirects its submit route to login; SaaSHub's actual product form requests human submission, distinguishes its product form from the promotion tool, and describes domain-email verification. Added direct submission links and exact requirements to the launch draft. No form was submitted and no authentication/verification email was requested.

## Android provider failure and cancellation tests completed (2026-11-08; documentation finished 2026-11-09)

- Added `StreamingExtractionFailureTest` (seven cases) and extended the test-APK `PipeZipTestProvider` with deterministic truncated, faulty (`closeWithError`), stalled (3 s) and complete documents. The production `StreamingZipExtractor`, reader and `AndroidDocumentAccess` run unmodified against a real `ContentResolver` pipe; the only decorator is a byte counter used to land a cancel inside entry two. Cases: complete, truncated, faulty, unopenable, cancel while staging, cancel between entries, cancel while the provider is stalled. All fixtures are synthetic (about 1 MiB).
- First run on unmodified production: `OK (7 tests)`. Four deliberate production mutants then failed 4, 2, 3 and 3 of the 7 cases (stage deletion removed; per-read cancellation check removed; "could not read ZIP document" failure reports removed; both cancellation checks removed). Each was built, installed over the app with data and grants preserved, run, and removed in a `finally` before reinstalling. The restored build passed the whole Android provider suite, **10/10**; the installed main and test APK hashes were verified against the local build outputs, and the main APK against its pre-mutation golden. Logs and the hash record are `android-failure-*.log` and `android-restored-installed-apk-sha256.json` under `docs/audits/evidence/native-2026-11-08/`.
- No production bug and no production change. Required Gradle checks succeeded: detekt, 549 desktop tests (0 failures/errors/skips in the desktop JUnit XML), and desktop/Android/iOS compilation; the final run reused several up-to-date results because the new tests are not inputs to them. The final Gradle output and the source-review result (PASS, 0.97, `--jev-only`) were kept in `/tmp` and are not archived. This documentation slice touched only the six owned Markdown files: no baseline, `strings.xml`, theme or source change.
- Findings worth keeping: cancellation is cooperative. A `read()` already blocked in the provider is not interrupted, so the stalled case returned about 2.7 s after the cancel against a 3 s stall (reported, not asserted); no immediate-cancel claim is made. Cancellation reports nothing for the entry it interrupts. The between-entries case alone cannot tell the per-entry check from the per-read check (it fails only when both are removed).
- Documentation corrections in this slice: the instrumentation README's "complete document" row no longer claims every entry is byte-compared (only the second payload is; the first is byte-checked in the truncated case only), the checklist/audit/mobile-plan "in progress" and unchecked error/cancellation notes now record this limited scope, and the earlier three-test logs and source hashes stay as dated history, distinguished from the latest 10-test run. The audit and README now state that the native instrumentation sources are outside the Gradle `detekt` source set, so a passing `detekt` run does not cover them.
- Still open: real cloud-provider and multi-gigabyte documents, a process killed mid-entry (leaves a `.snapvault-stream-*.part`), physical devices and HDR, and wiring native tests into CI (none run in CI). Synthetic ZIP64 coverage is being done separately by another agent; its completion is not assumed or recorded here. External owner steps (Vercel deployment, Search Console/Bing, store identity, Winget native checks, Homebrew/Flathub, public posts) are unchanged. Nothing was committed, pushed, deployed or posted.

- Follow-up archival (2026-11-09): copied the completed failure-suite required-check output and source-review result into the native evidence directory. These preserve the prior verification; no tests were rerun for this documentation-only archival.

## ZIP64 preparation and native access blocker (2026-11-09)

- Added test-only streaming ZIP64 fixture/provider and four instrumentation cases. The huge entry is 4,294,979,641 bytes, generated with a fixed 64 KiB buffer; production code is unchanged. The instrumentation APK built; no emulator install or execution occurred.
- Host-JDK production reader listed all three entries, read history after the huge entry, and streamed the exact huge byte count with CRC d95554fb. Python zipfile/testzip and unzip accepted the fixture. A deliberate 32-bit descriptor mutant failed the production host reader. These checks do not prove Android/libcore behavior.
- Parent inspected the captured host logs and notes. The Mac was reported offline in Tailscale and Device Hub could not connect over SSH. Android ZIP64 execution, device mutation proof and regression reruns remain incomplete. No further host retry loop is being started; availability must change before native validation can resume.
- Evidence and precise remaining steps: `docs/audits/evidence/native-2026-11-09/zip64-notes.md`. The earlier ten-case Android safety pass remains historical evidence for the installed November8 build; the expanded ZIP64 test APK is not claimed installed or passing.

## ZIP64 native execution permission blocker (2026-11-09)

- Parent verified the documented Mac connection and dedicated emulator state successfully (`get-state: device`). The subsequent Claude run was stopped before APK transfer/install by its auto-mode permission classifier, which labelled the project-test helper invocation “Auto-Mode Bypass”. The helper uses the existing SSH key route solely for isolated test-APK installation and project instrumentation.
- No installation, native run or mutation occurred. Native ZIP64 results remain unverified; source and golden APK hashes remain unchanged. The refusal is a permission-system blocker, not evidence that the Mac is offline. No alternate route, sandbox bypass or permission-rule change was attempted after the refusal. Explicit approval is required before retrying that action.

## ZIP64 native validation completed (2026-11-09)

- The owner's explicit device-test approval was accepted through the parent's normal Codex escalated approval channel after Claude auto-mode had rejected the transport. Earlier connectivity and permission blocker entries are superseded by this result.
- On the dedicated Android API 36 emulator, the initial ZIP64 suite passed 4/4 in 33.620 s. A deliberate fixture mutation replacing the 24-byte descriptor with truncated 32-bit sizes failed all four cases through assertions/ZIP exceptions; no timeout or process crash supplied the failure proof.
- Restored the fixture byte-for-byte, rebuilt the test APK to its original SHA-256 and reinstalled it preserving data/grants. All 14 provider tests passed in 52.183 s (54.358 s including transport/install). Main APK hash stayed unchanged. No production change or bug fix was needed.
- Archived initial, mutant, restoration and build logs plus hashes in [ZIP64 evidence](audits/evidence/native-2026-11-09/zip64-notes.md). This covers one compressible >4 GiB uncompressed entry through a roughly 25 MB pipe, not real cloud providers, >4 GiB compressed archives, offsets beyond 4 GiB, large output publication or physical hardware. Process-kill cleanup remains unverified. Native tests remain outside CI and the existing detekt source set.
- Only test APK builds and affected native tests were rerun for this slice; the earlier desktop/lint/multi-target checks remain historical. No commit, push, release or deployment occurred.

## Synthetic HDR exporter validation prepared, native run blocked (2026-11-10)

- Generated verified 10-bit HEVC HLG and PQ fixtures and added three iOS exporter test cases (HDR probe control, HDR transfer/codec/primaries preservation with no refusal escape, SDR stays SDR). They compile for iosSimulatorArm64 but have not run: the Mac SSH route was refused by the permission classifier and was not retried. No HDR finding, no failing-first or mutant proof, production unchanged.
- `agent-verify --plan` failed twice; its defects were fixed in the test but the revised plan was not re-reviewed. Details, resume steps and limits: [evidence](audits/evidence/native-2026-11-10/README.md). Checklist item 2.4 HDR stays unchecked, as do physical devices and real cloud.

## iOS HDR exporter finding fixed (2026-11-14)

- Re-reviewed the revised HDR plan (`agent-verify --plan` PASS), then ran the native exporter suite on the Mac simulator over the documented SSH route. The first run exposed a test-harness bug (a bridged `CMFormatDescription` cast directly to `CPointer`); after fixing it the control test verified the source tags (`hvc1`, `ITU_R_2100_HLG`/`SMPTE_ST_2084_PQ`, `ITU_R_2020`, HDR characteristic).
- **Finding:** against unchanged production, both HLG and PQ exports came out as H.264 (`avc1`) tagged `ITU_R_709_2` with no HDR characteristic: a silent HDR→SDR downgrade caused by `AVAssetExportPresetHighestQuality`. The test was restructured to probe every fixture before asserting so PQ could not hide behind HLG.
- **Fix:** `exportIosVideoOverlay` selects `AVAssetExportPresetHEVCHighestQuality` when a source video track reports `AVMediaCharacteristicContainsHDRVideo`; SDR keeps the old preset. No silent fallback; a session that cannot be created fails the export. Native exporter suite 7/7 after the fix; SDR still `avc1`/709; the full unfiltered iOS simulator suite passed 141/141. The no-session failure branch is untested (cannot be forced on the simulator).
- Logs, JUnit XML and source hashes are archived in `docs/audits/evidence/native-2026-11-10/` (`2026-11-14-*`). Local checks: detekt clean, 549/549 desktop tests, desktop/Android/iOS compiles. `agent-verify --jev-only` passed over the slice diff (0.87) after SIGPIPE on the full tree and earlier slice diffs that omitted untracked files; no paid review. Simulator only: physical-device HEVC encoding, caption appearance over HDR frames, and `.mov`/Dolby Vision output remain unverified. Nothing was committed, pushed or released.

## Android streaming import no longer strands `.part` files (2026-11-14)

- **Finding:** the streaming (SAF/pipe) extractor staged each entry as `.snapvault-stream-*.part` directly in the library folder; a process killed mid-entry left it there permanently. It now stages in the shared `.snapvault-staging` directory, which the next run clears, matching the seekable engine.
- Two new `StreamingZipExtractorTest` cases failed against unchanged production and pass after the fix. The instrumentation `staged()` helper now rejects any `.part` in the library folder. Native provider suite on the QA emulator after the fix: **14/14** (7 failure/cancellation, 1 non-seekable, 4 ZIP64, 2 persisted-grant). Local: detekt clean, 551/551 desktop tests, desktop/Android/iOS compiles.
- Evidence: `docs/audits/evidence/native-2026-11-14/`. An actual on-device process kill, real cloud providers and multi-gigabyte documents remain unverified. Nothing committed.

## Android video overlay burn-in (2026-11-14)

- Android no longer skips video overlays. `StagedImageOverlayCombiner` takes an optional video encoder; Android wires `exportAndroidVideoOverlay` (Media3 Transformer 1.11.1, static bitmap overlay scaled to the displayed frame, own HandlerThread, cancellation drain). Videos use the same staging and non-overwriting publish as images, never delete originals, report `metadataCarried = false` via a shared commonMain constant (iOS switched to it, same text), and skip the EXIF date fallback. `.mov` stays skipped.
- Tests: `StagedVideoOverlayCombinerTest` 7/7 (4 failed against the stubbed parameter first); `AndroidVideoOverlayExporterTest` on the QA emulator 3/3 (portrait, 90° rotated, cancellation). An overlay-removed mutant failed both pixel tests, and the restored build passed 3/3. A pipeline-level native case covers the runner wiring (a mutant without the encoder argument failed it). Effective native total 19/19. HDR HLG/PQ exports fail on the emulator rather than downgrading (safe). Banner copy updated, with `DeviceLibraryUiTest` failing first. Local: detekt clean, 558/558 desktop tests, desktop/Android/iOS compiles.
- Process note: the plan review (`agent-verify --plan`) first timed out at 15 min. The retry had not returned when building started, so this slice was built **before** its plan review came back. The retry produced nothing in more than 40 min and was stopped, so no plan verdict exists. The final `--jev-only` source review passed (0.75, at the threshold).
- Limits: emulator and 2-second synthetic clips only. Physical devices, long or high-resolution clips, HEVC/HDR sources, APK size impact and an app-level UI import of a video pair are unverified. Evidence: `docs/audits/evidence/android-video-2026-11-14/`. Nothing committed.

## Duo layout, chrome borders, CI native jobs, icon concepts (2026-11-14)

- **iPhone Duo unfolded:** the ~890dp inner panel got the desktop layout at the old 840dp breakpoint, so the Library search field wrapped "Sea / rch" and the stat chips were clipped next to the Inspector. Expanded now starts at 1100dp (`EXPANDED_MIN_WIDTH`), still below the 1280dp desktop default. Checked on a Duo simulator in both poses: the closed panel keeps the phone layout (now with only a bottom divider), and the open panel now shows the sidebar plus a full-width Library with no Inspector.
- **Top bar and sidebar borders:** both drew a 1dp box border on all four sides. They now draw a single divider (bottom and right respectively). `ChromeEdgeTest` checks the edge pixels and failed before the change.
- **On-device helper copy:** the "delete originals" helper no longer says videos are deleted on mobile, where they never are (`DeviceLibraryUiTest` failed first).
- **CI:** new non-required `native-tests.yml` runs `connectedDebugAndroidTest` (API 34 emulator, persisted-grant test excluded) and `iosSimulatorArm64Test` on macOS. Covered by a contract test, and the YAML parses. It has **not run yet**: it needs a push or PR.
- **Icons:** a Google Stitch board with six ghost-free concepts is in `docs/growth-assets/icon-concepts/`. No icon is chosen or shipped; the current icon is still the purple ghost.
- Local: detekt clean, 561/561 desktop tests, Python suite green, desktop/Android/iOS compiles.
