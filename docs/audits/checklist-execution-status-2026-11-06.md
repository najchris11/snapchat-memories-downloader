# Checklist execution status — 2026-11-06

This records implementation and access evidence, updated through 2026-11-08. Native results use isolated test apps and synthetic media; no new binary release or website deployment is claimed.

| Checklist | Current evidence | Outstanding requirement |
|---|---|---|
| 1.1–1.3 packaging and notes | Gradle/release configuration, release-note generator and Python regression suite | Shipping the changes requires a release |
| 1.4–1.6 JVM parity, Android safety, docs | Shared ZIP/scanner code, regression coverage, README/privacy/contribution docs | Native runtime validation remains separate |
| 2.1–2.3 toggles, source detection, manual updates | Common/JVM and Compose tests; all-target compilation | No background update requests introduced |
| 2.4 desktop enhancements | Stitching, offline timezone/map and disk planner code/tests | Confirmation remains required per destructive cleanup run |
| 2.4 Android SAF/MediaStore | Real picker/import/photo-combine/library/image-video MediaStore smoke; 10 native provider tests (latest run): persisted read-only reopen, pipe fallback and 7 synthetic failure/cancellation cases, each group with mutation proof | Real cloud/multi-gigabyte provider coverage, a process killed mid-entry and physical devices remain open; cancellation is cooperative, not immediate |
| 2.4 iOS overlays/Photos | Mac-built app, fixed compositor crash, UI pixel/audio/rotation checks, Photos allow/deny; 4 native exporter tests including cancellation and existing-output safety | Physical-device/HDR and provider variants remain unverified |
| 3.1–3.7 website | Companion checkout contains routes, metadata, navigation, installer funnel, screenshots, analytics and guides; Node tests, lint and production build pass | No deployment performed; analytics dashboard enablement remains external |
| 4.1 GitHub settings/releases | Exact description/topics, Discussions and v1.0.15 notes saved and independently read back using restored local CLI authentication | Complete; existing binary assets unchanged |
| 4.2 canonicalization/indexing | Host-restricted permanent redirect tested and emitted as 308 in production routes manifest | Deployment/live HTTP verification, Search Console/Bing ownership verification and sitemap submission; canonical GitHub homepage is saved |
| 4.3 store/package managers | Winget v1.0.15 candidates prepared; actual MSI checksum/properties, pinned schemas and nine rejecting controls verified | Windows native install/uninstall/sandbox checks, official Homebrew eligibility, human Flathub submission, store identity/signing and publication remain open |
| 4.4 distribution | Three draft vertical demo clips and caption guidance exist | Public posts and directory submissions require account access and owner execution; nothing posted |

Local source changes remain uncommitted. GitHub metadata and existing release notes were updated; no new binary release, website deployment, account registration or public launch post occurred. Remaining native and external requirements are tracked in `GROWTH_CHECKLIST.md`.

## 2026-11-08 native evidence update

The historical access blockers above were superseded for native deployment: the Mac reboot restored SSH, and isolated Android/iOS apps were built, installed and exercised. Android SAF import and image/video gallery publication passed with source hash retained. iOS import first reproduced a real Core Animation compositor crash; a native regression was added, failed before the renderer fix, and passed with Core Image filtering. The rebuilt ordinary UI import then passed; exported fixture pixels/dimensions/audio and add-only Photos publication were verified. Broader runtime matrices remain unchecked, and external owner/service actions are still open. See the current checklist and progress log.

### Expanded native checks (2026-11-08)

90° source rotation is now independently probed in the fixture, tested structurally through the native exporter and verified visually through the ordinary app output (160×120, audio retained, top overlay and uncomposited negative control). The native suite has 2 passing tests, with mutation proof. Actual Photos denial gives failure feedback without a crash; permission was restored and rotated video publication succeeded. Android two-archive folder import passed, and read-only persisted tree access remained after app process restart with no transient activity owner. Fresh-process provider-open instrumentation and active native cancellation were subsequently verified; see the safety update below. Standalone native test rendering has a documented green-frame limitation; structural passes are not pixel evidence.

### Native safety update (2026-11-08)

Four iOS native exporter tests pass, including active encoder cancellation with no late stage/output and byte-preserving existing-output rejection. Both safety cases failed under deliberate production mutations and passed after restoration. Two Android instrumentation tests pass on a dedicated Android 36 QA emulator: fresh-process ZIP reopening/parsing through the persisted read-only tree, and denial outside that tree. Both failed under a document-adapter mutant and passed after reinstalling restored production, preserving the grant. Local detekt, 549 desktop tests and desktop/Android/iOS compilation passed; source review passed. HDR/physical devices and broader cloud-provider/error matrices remain unverified. The original low-storage emulator and production data were preserved.

### Non-seekable provider update (2026-11-08)

The Android native suite now passes 3/3, including a real test-only ContentProvider pipe carrying 16 MiB of generated payload before history. Production seekable probing falls back to streaming, repeated reopening works, missing entries return no text and the cache remains unchanged. A disabled-fallback mutant failed the entry-list assertion; restored production passed. Cloud-service/multi-gigabyte provider behavior remains unverified. Required local checks passed; the test provider ships only in the test APK.

### Android provider failure and cancellation update (2026-11-08)

Extends the three-test provider result above (kept as dated history). `StreamingExtractionFailureTest` adds seven native cases that run the production `StreamingZipExtractor` against a test-APK provider through a real `ContentResolver` pipe: a complete document, truncation mid-entry, a provider-reported fault, an unopenable document, and cancellation while staging, between entries and while the provider is stalled. All seven passed on unmodified production. Four deliberate production mutants then failed 4, 2, 3 and 3 of the 7 cases (stage deletion removed; per-read cancellation check removed; "could not read ZIP document" failure reports removed; both cancellation checks removed). After restoring production and reinstalling, the whole Android provider suite passed **10/10** (`OK (10 tests)`), and the installed main and test APK SHA-256 values matched the local build outputs; the main APK also matched its pre-mutation golden. No production bug was found and no production code changed.

Limits: all fixtures are synthetic (about 1 MiB). Cancellation is cooperative: a `read()` already blocked in the provider is not interrupted, and the stalled case returned about 2.7 s after the cancel against a 3 s provider stall, so no immediate-cancel claim is made. That latency is reported, not asserted. Real cloud providers, multi-gigabyte documents, physical devices and a process killed mid-entry (which would leave a `.snapvault-stream-*.part`) are untested. The new instrumentation and provider sources sit outside the Gradle `detekt` source set (`androidInstrumentedTest`, `androidTest` and `iosTest` are not listed), so the existing `detekt` task does not lint them; a passing `detekt` run says nothing about those files. Required Gradle checks (detekt, 549 desktop tests with 0 failures/errors/skips, desktop/Android/iOS compilation) succeeded; several were `UP-TO-DATE` because the added tests are not inputs to them. Synthetic ZIP64 coverage is separate work and is not part of this result. Evidence: [`evidence/native-2026-11-08/`](evidence/native-2026-11-08/README.md).

### GitHub access and settings update (2026-11-08)

Supersedes the earlier invalid-token blocker: local CLI authentication now succeeds as the repository owner with admin permission. Section4.1 is complete: requested description/topics (prior topics preserved), Discussions enabled, and v1.0.15 notes backfilled. Canonical companion-repository homepage is also saved. Independent reads verified every value and exact notes; release asset IDs/digests/sizes are unchanged. No code deployment or release binary replacement occurred. Vercel/Search Console/Bing, store enrollment and package-manager/community publication remain pending; their blockers must be audited independently rather than attributed to GitHub authentication.

### Package publication audit (2026-11-08)

Homebrew's [official acceptance policy](https://docs.brew.sh/Package-Acceptance-Policy) requires public interest; current self-submission thresholds are 90 forks/watchers or 225 stars, with maintainer discretion. API evidence is 0 forks/watchers and 1 star. [Cask requirements](https://docs.brew.sh/Acceptable-Casks) also require Gatekeeper checks; unsigned direct downloads do not establish that gate. A third-party tap is possible but is distinct from official inclusion and has not been published.

Flathub's [generative-AI policy](https://docs.flathub.org/docs/for-app-authors/requirements#generative-ai-policy) prohibits generated/assisted manifests and agent submission/review interactions. This is a concrete human-maintainer step, not a missing GitHub token. No Flathub manifest or submission text was generated.

[Winget submission](https://learn.microsoft.com/en-us/windows/package-manager/package/repository) includes manifest validation and Windows sandbox install/uninstall tests. Current hosts have neither Windows/winget nor Wine/PowerShell. Manifest preparation remains possible and should be performed before declaring package work blocked. No package publication or third-party PR has occurred. No local Vercel CLI or companion-checkout project binding was found; search-account verification and store identity enrollment remain owner/external steps.

### Winget candidate evidence (2026-11-08)

Prepared candidates and provenance: [`distribution/winget/README.md`](../../distribution/winget/README.md). Parent independently reran pinned schemas, actual MSI checksum and consistency checks, and nine negative controls; all passed. This is preparation only. Native Windows checks and catalog publication remain unverified. Live GitHub settings and release-asset preservation have a durable read-back in [`evidence/github-growth-2026-11-08.json`](evidence/github-growth-2026-11-08.json).

### ZIP64 preparation and access update (2026-11-09)

Test-only ZIP64 fixture and four Android cases compile. Host-JDK production-reader checks and a malformed-descriptor negative control are captured in [ZIP64 evidence](evidence/native-2026-11-09/zip64-notes.md). Native Android execution is **unverified**: the connected Mac is offline/unreachable. No device was changed, and host Java results are not substituted for Android/libcore results. Real cloud providers and additional ZIP64 shapes remain outside this evidence.

### ZIP64 native execution completed (2026-11-09; supersedes access blockers above)

The dedicated API 36 emulator passed four ZIP64 tests (33.620 s). A malformed 32-bit descriptor failed all four cases; after byte-identical source and APK restoration, the full provider suite passed 14/14 (52.183 s). Owner approval was accepted through the parent’s normal Codex escalated approval channel. Only the test APK was installed, preserving data/grants; production was unchanged. [Logs and hashes](evidence/native-2026-11-09/zip64-notes.md) record the results and scope: >4 GiB uncompressed in a roughly 25 MB synthetic pipe, not real cloud or >4 GiB compressed/offset coverage. External and physical-device blockers remain open.
