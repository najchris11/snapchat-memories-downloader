# Synthetic HDR exporter validation — 2026-11-10, completed 2026-11-14

## 2026-11-14 result (supersedes the "blocked" status below)

**Finding: the iOS video overlay exporter silently downgraded HDR to SDR. Fixed; verified on the simulator only.**

The Mac SSH route worked on 2026-11-14 (the tree was rsynced to `/tmp/snapvault-hdr-20261114`, JDK `jbr-21.0.7`, simulator iPhone 17 Pro `CF9A84A0-…`, `:composeApp:iosSimulatorArm64Test --tests "*IosVideoOverlayExporterTest*"`). The revised plan was re-reviewed first: `agent-verify --plan` **PASS**.

| Run | Log | Result |
|---|---|---|
| 1 | `2026-11-14-run-initial.log` | 3 HDR tests failed with `ClassCastException: __NSCFType cannot be cast to CPointer` at the probe. **Test-harness bug, not a finding**: `formatDescriptions` holds bridged ObjC objects; fixed with `CFBridgingRetain` + `reinterpret` + `CFRelease`. |
| 2 | `2026-11-14-run-2.log`, `-run-2-junit.xml` | First real run. Control passed. HLG export: `avc1`, transfer/primaries/matrix `ITU_R_709_2`, `hdr=false`. The PQ case was hidden by the first assertion. |
| 3 | `2026-11-14-run-3-failing-first.log`, `-run-3.xml` | Test restructured to probe every fixture before asserting (nothing weakened), control also pins `ITU_R_2020` primaries. **Unchanged production fails 1/7**: both HLG and PQ export as `avc1` / `ITU_R_709_2` / `hdr=false`. Sources: `hvc1`, `ITU_R_2100_HLG` / `SMPTE_ST_2084_PQ`, `ITU_R_2020`, `hdr=true`. |
| 4 | `2026-11-14-run-4-fixed.log`, `-run-4.xml` | Fix applied: **7/7 pass**, 0 failures/errors/skips. HLG and PQ exports are `hvc1` with their source transfer function, `ITU_R_2020` primaries/matrix and `hdr=true`. SDR `source.mp4` stays `avc1` / 709 / `hdr=false`. |

Fix (`IosVideoOverlayExporter.kt`): when any source video track has `AVMediaCharacteristicContainsHDRVideo`, use `AVAssetExportPresetHEVCHighestQuality` instead of `AVAssetExportPresetHighestQuality`. SDR sources keep the old preset, so the existing four tests still cover unchanged behaviour. No fallback to the SDR preset: if the session cannot be created, the export fails (the constructor became `exportSessionWithAsset(...) ?: return false`). Run 3 is the failing-first proof, against the exact pre-fix production code. Its original logs were deleted by an `rsync --delete` before run 4, so it was re-run after the fix with only the exporter swapped back to the pre-fix file (sha256 `ddfa406c23e27c888cacd4534c5f0e42c9dc484aceeb620d067e9317455b19fe`): same 1/7 failure and identical probe values; the archived run-3 files are that reproduction. The fixed exporter was then restored on the Mac (sha256 matches run 4). Local checks after the fix: detekt clean, 549/549 desktop tests, desktop/Android/iOS compiles succeeded. Source hashes after the fix: `2026-11-14-source-sha256.txt`.

The CoreMedia string constants that were "unverified guesses" on 2026-11-10 are now verified by the control test against the ffprobe-tagged fixtures.

Untested branch: the new `exportSessionWithAsset(...) ?: return false` path (no session for the HEVC preset) cannot be forced on the simulator and has no test.

Full unfiltered iOS simulator suite after the fix: **141/141** passed (`2026-11-14-run-5-full-suite.log`).

Still unverified: physical devices (HEVC hardware encoder availability, older devices without one), how the SDR caption PNG looks composited over HDR frames (the standalone runner renders green frames, so no pixel claim), HDR10+/Dolby Vision metadata, and `.mov` HDR output. HDR bit depth is still inferred from codec + characteristic.

---

## Original 2026-11-10 notes (historical)

_`test-current-sha256.txt` below (c8fd5…) is superseded by `2026-11-14-source-sha256.txt` (test f4d542…)._


**Result: no HDR finding. Nothing was run on the simulator.** Whether `exportIosVideoOverlay` preserves HLG/PQ is unknown.

## What exists
- `hdr-hlg-ffprobe.json`, `hdr-pq-ffprobe.json`, `fixture-sha256.txt`: the generated fixtures (HEVC Main 10, yuv420p10le, bt2020nc/bt2020; HLG `arib-std-b67`, PQ `smpte2084`), verified with ffprobe.
- New test cases in `IosVideoOverlayExporterTest.kt` (original hash `test-original-sha256.txt`, current `test-current-sha256.txt`): probe-sees-HDR control, HDR export keeps transfer/codec/primaries/size/audio (completion required; refusal or downgrade fails), SDR stays SDR. `ios-test-compile-linux.log`: they compile for iosSimulatorArm64 on Linux. They have **never executed**, so nothing is proven about them: the expected strings (`ITU_R_2100_HLG`, `SMPTE_ST_2084_PQ`, `ITU_R_2020`) are unverified guesses at CoreMedia's values, and the CFRetain/CFBridgingRelease probe is untested at runtime. No failing-first run and no mutant exist.
- `plan-review-1-fail.log`, `plan-review-2-fail.log`: `agent-verify --plan` FAILED twice (vacuous "or refused" branch, CFRetain(NULL) abort, missing asserts/hashes). Those defects were fixed in the test afterwards; the revised plan has NOT been re-reviewed. The first test draft was written before the first review returned, against the requested order.

## Blocker
A reachability check `ssh -o BatchMode=yes najchris11@<mac tailscale ip> echo ok` was refused by the Claude auto-mode permission classifier ("Auto-Mode Bypass"). No retry, no alternative route. The simulator CF9A84A0-… was opened with `device_open` only. A Mac Gradle run needs the owner's explicit approval of that transport (as on 2026-11-09).

## Resume
`./gradlew :composeApp:iosSimulatorArm64Test --device CF9A84A0-8CDC-4801-BC70-10EF197CA895 --tests "*IosVideoOverlayExporterTest*"` on the Mac with JDK 21; read `HDR-PROBE` lines. If HDR tests fail, that is the finding: record exact values, then decide a minimal fix (full lint, desktop tests, three-target compile). If they pass first time, mutate the exporter (e.g. force SDR output) to prove they can fail, then restore.

## Limits
Standalone runner frames are green, so no pixel/visual claim is possible. Physical devices and real cloud providers remain unverified. HDR bit depth is inferred from HEVC codec + HDR characteristic, not read directly.

## Final `agent-verify --jev-only`
Run twice (`--spec spec.md --jev-only`): both produced no output and exit 141 (SIGPIPE). Treated as an **error, not a pass**; not retried further (Jev was already reported unavailable by the routing step). `final-agent-verify-jev-only.log` holds the captured output. No source review result exists for this slice. No Gradle lint/desktop/Android checks were run: production code is unchanged and only the iOS test klib compile was verified (it passed).

## 2026-11-14 final `agent-verify --jev-only`

Over the full working tree it exited 141 (SIGPIPE) with no output: an error. Slice diffs that left out the untracked test, checklist and progress-log files failed on missing content (`2026-11-14-final-agent-verify-jev-only.log`). With those files added as add-only hunks (`2026-11-14-reviewed-slice.diff`) it **PASSED**: least-implemented 0.87, most-broken restriction 0.36 (`2026-11-14-final-agent-verify-jev-only-pass.log`). This was Jev-only; no paid model review was run.
