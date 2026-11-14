# Android streaming staging fix — 2026-11-14

**Finding:** `StreamingZipExtractor` (the Android SAF/pipe import path) created each in-progress entry as `.snapvault-stream-*.part` directly in the library folder. Normal failures and cancellation deleted it in `finally`, but a process killed mid-entry left it there permanently, because no later run looked for it. The seekable engine already used the shared `.snapvault-staging` directory, which is cleared when opened.

**Fix:** the streaming extractor stages via `openStaging`/`closeStaging` (`jvmSharedMain/.../Staging.kt`), so a killed run's leftovers are removed on the next import and nothing in progress ever sits beside finished media. Mobile has not shipped, so there are no older root-level leftovers to migrate.

**Tests (failing first):** `StreamingZipExtractorTest.inProgressEntriesAreStagedOutsideTheLibraryFolder` (observes the folder during reads of an incompressible 256 KiB entry) and `aStagedFileLeftByAKilledRunIsRemovedByTheNextRun`. Both failed against unchanged production: a `.snapvault-stream-*.part` was observed in the library folder, and the stale staged file survived. A first draft of the first test could not fail (a compressible payload was fully read before staging began); it was strengthened before the fix.

**Native (QA emulator `SnapVault_QA_t3_24e5c88e`, Android 16, isolated `com.najdev.snapvault.roadmaptest`, installed with `-r` preserving grants):** the instrumentation `staged()` helper now asserts that no `.part` appears in the library folder and reads stages from `.snapvault-staging`. Results after the fix: `StreamingExtractionFailureTest` OK (7), `NonSeekableDocumentTest` OK (1), `Zip64PipeDocumentTest` OK (4), `PersistedDocumentAccessTest` OK (2): **14/14**. Logs: `android-*.log`. APK sha256: app `fb028185…21f32`, test `e7e180cd…0e14a` (built from a scratch copy with only `applicationId` changed).

**Limits:** no native production mutant was built for this slice (the desktop failing-first runs are the proof). An actual on-device process kill, real cloud providers and multi-gigabyte documents remain unverified. Native instrumentation sources are outside `detekt`.
