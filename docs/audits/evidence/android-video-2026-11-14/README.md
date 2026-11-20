# Android video overlay burn-in — 2026-11-14

Android used to skip every video/overlay pair. It now burns the caption/sticker PNG into `.mp4`/`.m4v` videos with Media3 Transformer 1.11.1 (`media3-transformer`, `-effect`, `-common`).

## Design
- `StagedImageOverlayCombiner` (jvmSharedMain) takes an optional suspend `videoEncoder`. Without one, videos are still `SkippedVideo`. With one, the encoder writes into `.snapvault-staging`, and the result is published through the same non-overwriting publish as images.
- A combined video **never deletes its originals**, even with "delete originals" on. It reports `metadataCarried = false` through the shared `VIDEO_METADATA_NOT_VERIFIED_WARNING` (moved to commonMain; iOS now uses the same constant, same text). Videos are left out of the EXIF date fallback because ExifInterface cannot write MP4.
- `.mov` stays `SkippedVideo` on Android (Media3 muxes MP4 only; no disguised bytes).
- `exportAndroidVideoOverlay` scales the overlay to the displayed frame (encoded size, swapped for 90°/270°), runs Transformer on its own `HandlerThread`, and on cancellation posts `cancel()` and waits for it before returning. Transformer chooses the output codec; HDR handling on Android is unverified.

## Evidence
- Desktop, failing first: `StagedVideoOverlayCombinerTest` (7 cases). With the parameter stubbed, 4 failed for behavioural reasons. The 3 that passed pin unchanged behaviour (no encoder → skip, `.mov` skip, existing output untouched). All 7 pass after the change, and the image-path `JvmSharedAndroidParityTest` passes unchanged.
- Native, QA emulator `SnapVault_QA_t3_24e5c88e` (Android 16, isolated `roadmaptest` build): `AndroidVideoOverlayExporterTest`, 3 cases. Portrait export; 90° rotated export (display 160×120); one video and one audio track; red overlay pixels across a top row in every column where the source is not already red (negative control), and no new red in the transparent area; cancellation returns with no late write.
  - `native-video-1.log`: first run, 1 failure. The test's single sample point was red in the source pattern itself (a test flaw); the check was changed to a whole row with per-column negative control.
  - `native-video-2.log`: **OK (3)**.
  - `native-video-mutant.log`: app built with the overlay effect removed. **Both pixel tests failed** (no red across the band); cancellation passed, as expected.
  - `native-video-restored.log`: restored source (byte-identical, `cmp`), rebuilt: **OK (3)**.
- Pipeline wiring: `theZipPipelineBurnsAVideoOverlayAndKeepsTheOriginals` drives `AndroidZipPipelineRunner.combineAll` (real staged path and publish) with `deleteOriginals = true`. It checks `combined`, `metadataCarried = false`, a video track in the output, and that both originals and no staging remain. A mutant without the runner's `videoEncoder` argument failed exactly this case (`native-video-mutant-wiring.log`). The restored build passed: `native-video-restored-all.log` has 17 passing exporter/provider cases plus 2 `PersistedDocumentAccessTest` failures caused by a mis-ordered `-e class` argument (it ran without its required grant arguments). Run correctly on its own (`native-persisted-2.log`) it passed 2/2, so the effective total is **19/19**.
- HDR characterisation (`native-video-3.log`): on the emulator both HLG (transfer 7) and PQ (transfer 6) exports **failed** rather than completing as SDR. That is the safe outcome: the pair is `Failed` and the originals are kept. No silent downgrade was observed. Behaviour on devices with HDR encoders is unverified; the test asserts that any completed HDR export keeps its transfer.
- UI copy: `banner_android_preview_body` no longer says video overlays stay separate. `DeviceLibraryUiTest` failed on the old copy first.

## Limits
- Emulator only, 2-second synthetic 120×160 H.264 clips. Not verified: physical devices and their hardware encoders, long or high-resolution clips, HEVC/HDR sources, memory use at 1080p+, and app-level end-to-end import through the UI.
- The cancellation case cannot tell the drain from no drain (it only proves no late write after return). No mutant targets it.
- Media3 adds to the APK size (not measured). The Dashboard "delete originals" helper text still says the original "photo or video" is deleted; on mobile, videos are always kept (the safe direction).

## Reviews
- Plan review (`agent-verify --plan`): **not obtained**. Attempt 1 timed out at 15 min with no output; attempt 2 produced nothing in more than 40 min and was stopped. Building started before any plan verdict, out of the workflow's order.
- Final source review (`agent-verify --jev-only` over `reviewed-slice.diff`, untracked files as new-file hunks): **PASS**, least-implemented 0.75 (at the threshold), most-broken restriction 0.24. No paid review.
