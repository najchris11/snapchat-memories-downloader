# SnapVault Growth, Product & Engineering Checklist

Reference audit: [`docs/audits/product-growth-audit-2026-11-06.md`](./audits/product-growth-audit-2026-11-06.md)

---

## 1. Small & Medium Code, Packaging & Doc Optimizations (`snapchat-memories-downloader`)

Items executable directly inside this repository without external credentials:

- [x] **1.1 Strip cross-OS bundled binaries from packaged installers (`composeApp/build.gradle.kts`, `.github/workflows/release.yml`)**
  - Exclude foreign-platform `bin/<platform>/**` archives when building native distributions (`-PbundleTargetPlatform=...` or packaging tasks) while preserving all committed `bin/**` archives on the `:composeApp:desktopTest` classpath for `ShippedToolBundlesTest` and `ShippedToolManifestTest`.
- [x] **1.2 Harden Windows & Linux installer metadata (`composeApp/build.gradle.kts`)**
  - Add stable Windows `upgradeUuid`, `perUserInstall = true`, `menuGroup`, `shortcut = true`, and `dirChooser = true`; add Linux `debMaintainer`, `menuGroup`, and `appCategory`.
- [x] **1.3 Generate rich, user-friendly GitHub Release notes automatically (`scripts/release_notes.py`, `.github/workflows/release.yml`)**
  - Prevent blank (`body: null`) releases by generating per-OS download guidance (`macos-arm64` vs `macos-x64`, `.msi`, `.deb`), calm macOS Sequoia Gatekeeper & Windows SmartScreen instructions, checksum verification info, and commit changelog since the previous `v*` tag.
- [x] **1.4 Share JVM `ZipReader`, unify export ZIP discovery/sorting across Desktop/Android/iOS, and implement Android `MediaScanner` (Issue #52)**
  - Hoist `ZipReader.kt` from `desktopMain` to `jvmSharedMain` (removing Android's empty stub), extract shared numeric export ZIP discovery/sorting into `commonMain` (fixing iOS folder mode beyond `mydata~*.zip` and `-10` vs `-2` ordering), and implement Android `MediaScanner` (`scanMediaFiles`, `loadFullImage`, `loadThumbnail`).
- [x] **1.5 Harden Android overlay combine staging, non-overwriting publish, & metadata fallback callbacks (Issues #51 & #64)**
  - Stage combined Android outputs in `.snapvault-staging`, publish without overwriting concurrently created files via `Os.link` (`minSdk 24`-compatible), gate original deletion on carried metadata, preserve PNG alpha on `.png` composites, and wire `onMetaStart` / `onMetaError` date-fallback writes.
- [x] **1.6 Refresh `README.md`, fix stale `CLAUDE.md` notes, and add `PRIVACY.md` & `CONTRIBUTING.md`**
  - Add website (`https://snapvault.naj-dev.com`) and tutorial walkthrough links to `README.md`, update macOS Sequoia 15+ Gatekeeper steps (`Privacy & Security → Open Anyway`), clarify `arm64` vs `x64` installer choice, correct outdated `strings.xml` and `iosMain` `VideoPlayer` notes in `CLAUDE.md`, and add `PRIVACY.md` (zero-telemetry policy for Store reviews) + `CONTRIBUTING.md`.

---

## 2. Larger Self-Contained Feature Enhancements (`snapchat-memories-downloader`)

Larger code changes suitable for dedicated subagent implementation with full TDD coverage:

- [x] **2.1 "Keep original unmerged files" toggle when combining overlays (`DashboardScreen`, `DashboardViewModel`, `VaultIndex`)**
  - Expose user control over `deleteOriginals` under *Combine captions & stickers* (preserving existing default behavior with a named constant and updating disclosure copy + index handling when originals are kept).
- [x] **2.2 Unified / auto-detecting export source picker (Issue #54)**
  - Simplify export selection so choosing a folder of ZIPs, a single `.zip`, an extracted export folder, or a `memories_history.json`/`.html` file auto-configures the import mode with clear UI feedback.
- [x] **2.3 Opt-in manual "Check for updates" action in Settings (`SettingsScreen`)**
  - Strictly click-triggered check against GitHub Releases (`releases/latest`) comparing against `AppBuildConfig.VERSION` with zero automatic background network requests on launch.
- [ ] **2.4 Future roadmap features (post-v1.0.16)**
  - [x] Desktop split-video stitching (explicit playback-order selection, stream compatibility checks, lossless FFmpeg concat, originals retained; FFprobe required)
  - [x] Desktop offline GPS-to-timezone lookup (`OffsetTimeOriginal` plus matching local photo capture times; historical DST; native mobile pending)
  - [x] Interactive offline GPS Memories map in `LibraryScreen` (bundled land outlines, pan/zoom, accessible selection, backwards-compatible coordinate index)
  - [x] Pre-run disk-space planner UI & verified archive-by-archive low-space cleanup (D20)
    - [x] Read-only pre-run space estimate and source/output feedback
    - [x] Streaming SHA-256 verification, unknown-member retention, verified non-overwriting history backups, existing opt-in cleanup tests
    - [x] Activate the production confirmation-only cleanup offer — explicitly approved by the owner and applied; every run still requires in-app confirmation before archive deletion.
  - [ ] iOS `AVAssetExportSession` video overlay burn-in + `PHPhotoLibrary` ("Save to Apple Photos")
    - [x] Explicit add-only Photos publication with file-based image/video requests, authorization/failure tests, and iOS compilation.
    - [x] AVFoundation ZIP video overlay exporter with source composition/audio, unique staging, no-overwrite publication, cancellation drain, and originals retained.
    - [x] Mac-built isolated simulator app: reproduced/fixed native video compositor crash; real native exporter regression and ordinary UI import passed. Fixture pixel placement, dimensions and audio verified; add-only Photos image/video saves succeeded.
    - [x] 90° rotated video validated in native structural tests and actual UI output (dimensions/audio/top-overlay pixels); Photos denial displayed failure without quitting.
    - [x] Real native active-export cancellation drains the encoder without late output; existing output remains byte-for-byte unchanged. Both safety tests failed under deliberate mutations and passed after restoration.
    - [x] Synthetic HDR (simulator): HLG and PQ sources were silently exported as 8-bit H.264 Rec.709 SDR under `AVAssetExportPresetHighestQuality`. HDR sources now use `AVAssetExportPresetHEVCHighestQuality` (no SDR fallback); failing-first run 1/7 failed, fixed run 7/7 passed, SDR unchanged. See [HDR evidence](audits/evidence/native-2026-11-10/README.md).
    - [ ] Remaining native matrix: physical-device behavior (HEVC encoder availability, caption appearance over HDR frames) and provider variants remain unverified.
  - [x] Android video overlay burn-in (Media3 Transformer): `.mp4`/`.m4v` pairs are staged and published without overwriting; video originals are always kept and metadata is not claimed; `.mov` still skipped. Desktop tests 7/7 (4 failed first); native emulator 3/3, overlay-removed mutant failed both pixel tests. Emulator and synthetic clips only. See [evidence](audits/evidence/android-video-2026-11-14/README.md).
  - [ ] Android streaming SAF `DocumentFile` reader + `MediaStore` gallery publishing
    - [x] Stream selected ZIP documents without archive caching; persist read grants; unify file/tree pickers and source labels.
    - [x] Disclose the app-owned working library and uninstall lifecycle in Dashboard, Library and Settings.
    - [x] Explicit gallery copy on Android 10+ using pending MediaStore rows; preserve originals and roll back failed/cancelled publications.
    - [x] Android emulator smoke: native document picker → ZIP detection → streaming import → photo combine/library → image/video MediaStore save; published rows confirmed, source ZIP hash unchanged.
    - [x] Two-archive SAF folder import passed; after app restart the tree retained read-only persisted access with no transient activity owner.
    - [x] Fresh-process Android instrumentation reopens both ZIPs using persisted read-only access, parses entries without archive caching, and rejects an ungranted outside document. Both tests failed under deliberate mutations and passed after restoration.
    - [x] Real pipe-only test provider: 16 MiB streamed payload, non-seekable directory fallback, repeated reopening/history lookup, missing-entry handling and unchanged cache. Deliberately disabling the production fallback failed the test; restored native suite passed 3/3.
    - [x] Android provider runtime error and cancellation (synthetic, bounded scope): seven native cases through a real `ContentResolver` pipe cover a complete document, truncation mid-entry, a provider-reported fault, an unopenable document, cancellation while staging, cancellation between entries and cancellation while the provider is stalled. Four production mutants failed 4, 2, 3 and 3 of the 7 cases; after restoration the full Android provider suite passed 10/10. Cancellation is cooperative: a read already blocked in the provider is not interrupted (about 2.7 s observed against a 3 s stall), so no immediate-cancel claim is made. No production bug was found. See [instrumentation README](../composeApp/src/androidInstrumentedTest/README.md) and [evidence](audits/evidence/native-2026-11-08/README.md).
    - [x] Validated bounded-buffer ZIP64 pipe fixture (>4 GiB uncompressed) on Android API 36: four cases passed, a malformed 32-bit descriptor made all four fail, then source/APK restoration and the full provider suite passed 14/14. The compressed stream is about 25 MB; this does not cover >4 GiB compressed archives or offsets. See [ZIP64 evidence](audits/evidence/native-2026-11-09/zip64-notes.md).
    - [x] Killed-import leftovers: streamed entries were staged as `.snapvault-stream-*.part` directly in the library folder and never cleaned up after a process kill. They now stage in `.snapvault-staging`, cleared by the next run. Two desktop regression tests failed first; native provider suite 14/14 on the QA emulator afterwards (2026-11-14). See [evidence](audits/evidence/native-2026-11-14/README.md).
    - [ ] Remaining provider matrix: real cloud-service and multi-gigabyte documents, an actual on-device process kill, and any provider-specific latency or error behaviour remain unverified. Synthetic ZIP64 coverage is tracked separately and is not claimed here.

---

## 3. `snapvault-web` Repository Checklist (`najchris11/snapvault-web`)

Changes to apply in the `snapvault-web` Next.js repository:

- [x] **3.1 Search indexation plumbing:** Add `src/app/robots.ts`, `src/app/sitemap.ts`, and `alternates: { canonical: './' }` in `src/app/layout.tsx`.
- [x] **3.2 Fix broken `/tutorial` navigation & mobile menu (`src/components/nav.tsx`):** Change `#how-it-works`, `#features`, `#privacy`, `#faq` to `/#...` and add a mobile navigation drawer (`<768px`).
- [x] **3.3 Direct OS/CPU-aware download funnel (`src/lib/links.ts`, `hero.tsx`, `platforms.tsx`):** Link directly to latest OS/arch installers (`macos-arm64.dmg`, `macos-x64.dmg`, `windows-x64.msi`, `linux-x64.deb`) with a post-click install modal covering Gatekeeper / SmartScreen.
- [x] **3.4 Mobile visitor handoff banner:** Show phone visitors a 2-step flow (*1. Request your Snapchat export now → 2. Share/email SnapVault to your Mac or PC*).
- [x] **3.5 Real screenshots & current UI copy (`hero.tsx`, `tutorial/page.tsx`):** Replace CSS mockup's pre-audit toggle names with real Dashboard + Library screenshots and a before/after overlay/EXIF GIF.
- [x] **3.6 Analytics, OpenGraph, JSON-LD & Linux docs:** Add privacy-friendly analytics (`@vercel/analytics` or Plausible), switch `twitter.card` to `'summary_large_image'` with a `<300 KB` `1200×630` banner, add `SoftwareApplication` / `FAQPage` / `VideoObject` JSON-LD, mention `perl` alongside `ffmpeg` on Linux, pair `SnapVault` with `Snapchat Memories Downloader & Backup` in `<title>`, and add a `LICENSE` file.
- [x] **3.7 High-intent SEO guide & comparison routes:**
  - [x] `/guides/snapchat-5gb-limit-january-2027`
  - [x] `/guides/fix-snapchat-export-missing-dates-overlays`
  - [x] `/guides/import-snapchat-memories-to-apple-photos-google-photos`
  - [x] `/vs/free-snapchat-memories-exporter`

---

## 4. Owner / External Actions Requiring Credentials or Manual Input

Items that cannot be completed autonomously from code alone:

- [x] **4.1 GitHub Repo Settings (`najchris11/snapchat-memories-downloader`):**
  - Completed and read back on 2026-11-08: local GitHub CLI authentication now works as `najchris11` with admin access. Description, all requested topics (preserving prior topics), Discussions and v1.0.15 notes are live. Release asset IDs, digests and sizes are unchanged.
  - Set **About → Description**: *"Free, 100% offline desktop app to download, restore EXIF dates & GPS, and burn in caption overlays for Snapchat Memories exports (macOS, Windows, Linux)."*
  - Add **Topics**: `snapchat-export`, `snapchat-downloader`, `exif`, `exiftool`, `ffmpeg`, `kotlin-multiplatform`, `compose-multiplatform`, `compose-desktop`, `backup`, `photo-management`.
  - Enable **GitHub Discussions** (`Settings → Features → Discussions`).
  - Backfill release notes onto the existing `v1.0.15` GitHub Release page so current visitors aren't met with an empty page before `v1.0.16` ships.
- [ ] **4.2 Search Console & Domain Canonicalization (`snapvault-web`):**
  - Verify `https://snapvault.naj-dev.com` in **Google Search Console** and **Bing Webmaster Tools**, submit `https://snapvault.naj-dev.com/sitemap.xml`, and request indexing for `/` and `/tutorial`.
  - [x] Prepared a tested host-specific permanent redirect in `snapvault-web/next.config.mjs`; production deployment and live verification remain pending.
  - [x] Updated and read back `najchris11/snapvault-web`'s GitHub `homepage` as `https://snapvault.naj-dev.com` (2026-11-08).
  - [ ] Deploy/configure Vercel to `308` redirect `snapvault-web-three.vercel.app` → `https://snapvault.naj-dev.com`.
- [ ] **4.3 Zero-Cost Windows SmartScreen Elimination & Package Managers:**
  - Register a free individual **Microsoft Partner Center** account and submit SnapVault as an MSIX to the **Microsoft Store** (automatic Microsoft signing removes SmartScreen warnings at $0).
  - Publish manifests to **Homebrew Cask**, **Winget (`microsoft/winget-pkgs`)**, and **Flathub**.
    - Homebrew official-tap eligibility is currently unmet: repository has 1 star, 0 forks and 0 watchers; current self-submission thresholds are 225 stars, 90 forks or 90 watchers, subject to maintainer exceptions. Casks must also pass Gatekeeper checks. [Acceptance policy](https://docs.brew.sh/Package-Acceptance-Policy), [Cask requirements](https://docs.brew.sh/Acceptable-Casks).
    - Flathub requires a human-authored manifest and human submission/review interactions; agents must not generate or submit those materials. A maintainer must handle publication and disclose generated application material accurately. [Current policy](https://docs.flathub.org/docs/for-app-authors/requirements#generative-ai-policy).
    - [x] Winget v1.0.15 candidate manifests prepared under `distribution/winget/`; actual MSI properties/checksum and pinned upstream schemas independently verified, with nine rejecting negative controls.
    - [ ] Winget native `winget validate`, install/uninstall and sandbox checks require Windows; no submission or publication is claimed. [Submission checks](https://learn.microsoft.com/en-us/windows/package-manager/package/repository).
  - *(Optional paid)* Enroll in Apple Developer Program ($99/yr) if you decide to notarize direct `.dmg` downloads and ship iOS to TestFlight / App Store.
- [ ] **4.4 Pre-January-2027 Distribution Push:**
  - [x] Created 3 draft vertical demo clips (18 seconds, 1080×1920) using current app screenshots and generated samples, with factual January 2027 archiving copy. See `docs/growth-assets/README.md`; clips are prepared, not posted.
  - [x] Prepared release-specific directory fields and reusable launch facts in `docs/growth-assets/launch-drafts.md`; independently checked v1.0.15 facts and current Snapchat/HN primary sources. HN copy must be human-written; Reddit rules could not be read automatically.
  - Post visual before/after launch threads to `r/DataHoarder`, `r/SnapchatHelp`, `r/selfhosted`, `r/immich`, `r/opensource`, `r/macapps`, and Hacker News (`Show HN`).
  - Directory access verified 2026-11-08: AlternativeTo requires verified email; OpenAlternative requires sign-in; SaaSHub asks for human submission. Direct form links and requirements are in the launch draft.
  - Submit SnapVault to **AlternativeTo**, **SaaSHub**, and **OpenAlternative** as a free open-source alternative to *FixMyExport*, *SnapEasy*, *KeepMySnaps*, and *MemoriesExport*.


## Iteration 3 delivery notes

- Section 3 changes are in the clean-start local companion checkout at `~/Documents/GitHub/snapvault-web`; they are built and tested but uncommitted and not deployed.
- Website analytics is website-only, respects Do Not Track, and strips URL queries/fragments. Vercel Web Analytics must be enabled in the project dashboard before deployment collects data. No app telemetry was added.
- VideoObject metadata omits an unverified publication date; no upload date or ratings were fabricated. Google rich-result eligibility is not promised.
- Mobile status supersedes the original Iteration 3 host outage: Android SAF/MediaStore and iOS Photos publication are implemented and cross-compiled. Android and iOS simulator smoke validation passed on 2026-11-08 after the Mac connection recovered; HDR/physical-device and real cloud/multi-gigabyte provider coverage remains open. Synthetic Android provider error/cancellation checks are complete for the limited scope recorded above (10/10 native provider tests); they do not cover real cloud providers.
- Desktop timezone data adds a roughly 22 MiB database JAR plus dependencies, loaded lazily during precise GPS metadata writing. Full license/source notices are in `THIRD_PARTY_LICENSES.md` and `docs/licenses/`.
