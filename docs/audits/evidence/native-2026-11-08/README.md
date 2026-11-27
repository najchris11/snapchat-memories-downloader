# Native QA evidence — 2026-11-08

These are captured results from isolated simulator/emulator test apps using synthetic media. They are historical evidence, not a claim that later changes have been rerun. The source hashes in the summary describe the tree when these reports were archived, not a released commit.

- `ios-exporter-junit.xml`: four real iOS simulator exporter cases; host name normalized. Audio/source retention, rotated dimensions, active cancellation and existing-output preservation. Standalone rendering has the green-frame limitation described in the fixture README; these are not automated pixel assertions.
- `android-provider-restored.log`: native Android 36 provider suite, three passing cases after restoring production. Persisted read-only access, denial outside the selected tree, and non-seekable pipe fallback.
- `android-pipe-mutant.log`: deliberate mutation replacing the production streaming-entry fallback with an empty list; the pipe test failed at its expected-entry assertion. The mutant was removed and the checked application reinstalled before the restored suite.
- `verification-summary.json`: required local Gradle check tasks, desktop JUnit totals and current source hashes at archival. It predates the failure/cancellation suite: its `androidNativeTests: 3` and source hashes are archival and are not updated, and it records no hash for `StreamingExtractionFailureTest.kt`.

## Latest Android provider run (10 tests) — failure and cancellation

These logs are newer than the three-test files above. The three-test logs (`android-provider-restored.log`, `android-pipe-mutant.log`) and the hashes in `verification-summary.json` stay as the dated record of that earlier run; the **latest Android result is the 10-test run** below. All were captured on the dedicated Android 36 QA emulator with synthetic fixtures. Counts below were re-read from the logs.

- `android-failure-first-green.log`: the seven `StreamingExtractionFailureTest` cases on unmodified production, `OK (7 tests)`. The stalled case reports `cancelLatencyMillisWhileProviderStalled=2699`.
- `android-failure-mutant-stage-delete.log`: `staged?.delete()` removed; 4 of 7 fail (faulty, truncated, cancel while staging, cancel while stalled), each on a leftover `.snapvault-stream-*.part`.
- `android-failure-mutant-loop-check.log`: per-read cancellation check removed; 2 of 7 fail (cancel while staging, cancel while stalled), each with `second.jpg must not be reported as written`.
- `android-failure-mutant-document-failure-report.log`: the "could not read ZIP document" failure reports removed; 3 of 7 fail (faulty, truncated, unopenable).
- `android-failure-mutant-no-cancel-checks.log`: both cancellation checks removed; 3 of 7 fail (cancel between entries, cancel while staging, cancel while stalled).
- `android-failure-restored-suite.log`: production restored and reinstalled; `NonSeekableDocumentTest`, `PersistedDocumentAccessTest` and the failure suite together, `OK (10 tests)`.
- `android-restored-installed-apk-sha256.json`: recorded SHA-256 of the installed main and test APKs, which the parent verified against the local build outputs; the main APK also matches its pre-mutation golden.

Scope and limits: cancellation is cooperative. A `read()` already blocked in the provider is not interrupted, so the stalled case returned only after the 3 s stall (about 2.7 s after the cancel); latency is reported by the log, not asserted, and no immediate-cancel claim is made. The fixtures are synthetic (about 1 MiB through a test-APK provider), so real cloud providers, multi-gigabyte documents, physical devices and a process killed mid-entry remain unverified. The Gradle check output is archived as `android-failure-required-checks.log` (required tasks were up-to-date after restoring production), and the read-only source review as `android-failure-source-review.log` (PASS 0.97). The desktop JUnit total was 549 with zero failures/errors/skips; the existing `detekt` task does not cover the instrumentation sources. The ZIP64 work is separate and is not reflected here.

Reproduction prerequisites/commands: [Android instrumentation](../../../../composeApp/src/androidInstrumentedTest/README.md), [iOS media fixtures](../../../../composeApp/src/iosTest/resources/video-overlay/README.md), and [progress log](../../../PROGRESS_LOG.md). The native iOS runner used `:composeApp:iosSimulatorArm64Test --device <simulator-UDID> --tests "*IosVideoOverlayExporterTest*"` with the Mac JDK 21. No physical-device, HDR or real cloud-provider pass is implied.
