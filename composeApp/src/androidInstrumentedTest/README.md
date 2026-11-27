# Persisted SAF access integration test

This suite checks real Android document-provider access in a fresh instrumented target process. It does not launch an Activity or obtain a new picker grant.

Prerequisites on an isolated debug app:

1. Put the synthetic two-archive folder in Downloads. It contains `mydata-2.zip` with the photo/video fixtures and `mydata-10.zip` with `memories/2026-11-08_extra.jpg`.
2. Select that folder through SnapVault's native folder picker and confirm read access. Import it once and restart the app. The system must show persisted read permission without an activity owner.
3. Leave `snapvault-native-validation.zip` outside the granted folder as an existing unselected document. Do not grant separate access to it.

Build and install the instrumentation APK without reinstalling/clearing the target app (which would destroy the persisted grant being tested):

```sh
ANDROID_HOME=/opt/android-sdk ./gradlew :composeApp:assembleDebugAndroidTest
adb install -r -t composeApp/build/outputs/apk/androidTest/debug/composeApp-debug-androidTest.apk
```

Run with the actual installed target/test IDs (the normal test ID is `com.najdev.snapvault.test`; isolated QA builds use `com.najdev.snapvault.roadmaptest.test`):

```sh
adb shell am instrument -w -r \
  -e class com.najdev.snapvault.PersistedDocumentAccessTest \
  -e nativeFixtureTree 'content://com.android.externalstorage.documents/tree/primary%3ADownload%2FSnapVaultNativeQA' \
  -e nativeOutsideDocument 'content://com.android.externalstorage.documents/document/primary%3ADownload%2Fsnapvault-native-validation.zip' \
  com.najdev.snapvault.roadmaptest.test/androidx.test.runner.AndroidJUnitRunner
```

The suite fails explicitly if arguments/grants/fixtures are missing; it never reports an unprepared device as a passing permission test. Verify `OK (2 tests)` in the instrumentation output, since `am instrument` shell status alone is not proof of passing assertions. It checks persisted read-only access, numeric archive ordering, reopening and parsing the stream, no whole-ZIP cache copy, and denial outside the selected tree.

Device UI setup must use the bound Device Hub CLI in agent sessions. `adb` here is only for build installation and the project's native test runner.

## Non-seekable provider test

`NonSeekableDocumentTest` needs no picker grant or external account. Its provider is declared only in the instrumentation manifest and lives in the test APK. The provider uses Java because that separate process cannot load the target application's Kotlin runtime. It generates a ZIP containing 16 MiB of uncompressed payload followed by a small history document, using a fixed 8 KiB buffer and an OS pipe. This is deterministic pipe coverage, not a claim about any particular cloud service or multi-gigabyte export.

Run it independently with `-e class com.najdev.snapvault.NonSeekableDocumentTest` and expect `OK (1 test)`. It exercises source detection, the failed seekable-directory probe, the production streaming fallback, repeated reopening, missing-entry handling, and an unchanged app cache. The test has a 30-second timeout.

On 2026-11-08 the case passed on Android 36. A temporary production mutant that returned an empty list instead of using the streaming fallback failed at the expected entry-list assertion. After restoring production, this case and both persisted-grant cases passed together (`OK (3 tests)`; that was the count at the time, and the latest combined run, with the failure suite below, is 10). No production provider component is installed by the main APK.

## Provider failure and cancellation (`StreamingExtractionFailureTest`)

Same provider and no picker grant or account. It runs the production `StreamingZipExtractor`/`StreamingZipReader` against documents served by `PipeZipTestProvider` through a real `ContentResolver` pipe. All fixtures are synthetic: a 4 KiB, a 1 MiB and a 2 KiB incompressible entry (about 1 MiB total, rebuilt in memory by the provider, never stored). Run with `-e class com.najdev.snapvault.StreamingExtractionFailureTest` and expect `OK (7 tests)`; each case has a 30-second timeout.

| Case | Provider behaviour | Asserted outcome |
|---|---|---|
| complete document | full archive | all three entries published under their names; only the second entry (1 MiB) is byte-compared with its regenerated payload, the first and third are checked by name and success only; no stage, no app-cache copy |
| truncated | closes the pipe cleanly about 100 000 bytes into entry two's record | entry one kept and byte-compared with its regenerated payload; entries two and three reported failed with no output path; only `first.jpg` in the folder, no `.part`; reopening reports entry one skipped and entry two failed again, and the reopened stream still starts with the ZIP local-header signature (only those four bytes are checked) |
| faulty | reliable pipe whose writer calls `closeWithError` mid-entry | same outcome as truncated, except entry one's bytes are not compared here (success and folder listing only). It was not checked whether the reader sees an `IOException` or plain EOF; the extractor treats both as failure |
| unopenable | `FileNotFoundException` from `openFile` | every requested entry fails, nothing written |
| cancel while staging | none | a `.part` is active in `.snapvault-staging` (never in the library folder) when the cancel arrives; the extractor stops at its next read, publishes nothing further, removes the stage, and nothing appears after it returns |
| cancel between entries | none | cancelling from the progress callback after entry one publishes starts no stage for entry two |
| cancel while stalled | writes a prefix, sleeps 3 s, then the rest | cancelled; stalled entry not published; no stage; no late output |

The cancel trigger in the two staging cases is a byte-counting decorator around the real provider stream, so the cancel lands at a known point inside entry two. Nothing replaces the pipe, the reader or the extractor.

What this does and does not show:

- Cancellation is cooperative. The extractor checks it before every entry and before every read. A `read()` already blocked in the provider pipe is not interrupted by `cancel()`: in the stalled case the extractor returned only after the provider's 3 s stall ended (about 2.7 s after the cancel on the Android 36 emulator; reported as the `cancelLatencyMillisWhileProviderStalled` status line and deliberately not asserted). No claim is made about latency against any real cloud provider.
- Cancellation reports nothing for the entries it interrupted (neither success nor failure); the tests assert only that none is reported as written.
- Since 2026-11-14 streamed entries are staged in `.snapvault-staging`, which the next run clears, so a process killed mid-entry no longer leaves a `.part` beside finished media. `StreamingZipExtractorTest` covers the staging location and the next-run cleanup (both failed before the fix). An actual process kill on a device has not been exercised.
- The between-entries case cannot distinguish the per-entry check from the per-read check by itself (a stage created and removed in the same call is not observable); it only fails when both checks are removed.

Verified on 2026-11-08 on the dedicated Android 36 QA emulator. The suite passed on unmodified production, then each production mutant below was built, installed over the app (data and grants preserved), run, and removed in a `finally` before reinstalling the restored build:

| Mutant (in `StreamingZipExtractor`) | Failing cases |
|---|---|
| remove `staged?.delete()` | faulty, truncated, cancel while staging, cancel while stalled (4 of 7) |
| remove the per-read `ensureActive()` | cancel while staging, cancel while stalled (2 of 7) |
| remove the "could not read ZIP document" failure reports | faulty, truncated, unopenable (3 of 7) |
| remove both `ensureActive()` calls | cancel between entries, cancel while staging, cancel while stalled (3 of 7) |

After restoring, `NonSeekableDocumentTest`, `PersistedDocumentAccessTest` and this suite passed together (`OK (10 tests)`). Logs: `docs/audits/evidence/native-2026-11-08/android-failure-*.log`. No production bug was found, so no production code changed. Real cloud providers, multi-gigabyte documents and physical devices remain untested.

## Static analysis

The Gradle `detekt` task does not cover this directory: its source set lists `commonMain`, `desktopMain`, `androidMain`, `iosMain`, `jvmSharedMain`, `commonTest` and `desktopTest` only (see `build.gradle.kts`). `androidInstrumentedTest`, the Java `androidTest` provider and `iosTest` are therefore not linted, and a green `detekt` run is not evidence about them. Do not add them to the detekt baseline; widening the source set is a separate change.
