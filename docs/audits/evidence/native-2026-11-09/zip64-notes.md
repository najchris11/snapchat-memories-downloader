# Synthetic ZIP64 pipe coverage — 2026-11-09

**Status: Android validation completed on the dedicated API 36 emulator (`emulator-5556`).**
The initial four ZIP64 cases passed (runner time 33.620 s). Replacing the 24-byte ZIP64 descriptor with a
16-byte descriptor carrying truncated 32-bit sizes made all four fail, with descriptor assertions and
ZIP exceptions rather than timeouts or a process crash. The fixture was restored byte-for-byte and the
rebuilt test APK SHA-256 matched its pre-mutation value. The restored full provider suite passed **14/14**
(runner time 52.183 s; transport including installation 54.358 s).

Earlier Mac connectivity and Claude auto-mode permission failures below are historical. Following the owner's
explicit approval, the parent used the normal Codex escalated approval channel for the constrained test
transport; it was accepted. Only the test APK was replaced, preserving application data and persisted grants.
No production source changed, and no other emulator was touched.

## Native evidence

- `zip64-native-initial.log`: `OK (4 tests)`.
- `zip64-native-mutant-32bit-descriptor.log`: `Tests run: 4, Failures: 4` (transport 23.835 s).
- `zip64-native-restored14.log`: `OK (14 tests)`, covering ZIP64, non-seekable access, seven extraction
  failure/cancellation cases and two persisted-grant cases.
- `zip64-native-mutant-build.log` and `zip64-native-restored-build.log`: successful test APK builds.
- `zip64-restored-sha256.json`: restored fixture, test APK and unchanged main APK hashes.

This is malformed-fixture sensitivity proof, not a production-code mutation. Earlier production-mutant
coverage for extraction failure/cancellation is recorded in the November 8 evidence.

## What was added (test APK only; no production change, no baseline/strings/theme edits)

- `composeApp/src/androidTest/java/com/najdev/snapvault/Zip64Fixture.java` — pure-Java streaming writer for a real ZIP64
  archive. Entry 1 `memories/huge.bin` inflates to 4 GiB + 12 345 B (4 294 979 641 B): local header with the
  data-descriptor flag, deflated data from one fixed 64 KiB pattern buffer, a **24-byte ZIP64 data descriptor** (8-byte
  sizes), central-directory sizes escaped to 0xFFFFFFFF with extra field 0x0001, ZIP64 end record + locator + EOCD.
  Entries 2–3 (`memories/after.jpg` 1500 B stored, `memories_history.json` 18 B stored) follow it. Nothing is stored:
  archive and payload are generated through a bounded buffer; the stream is ≈25 MB.
- `Zip64PipeTestProvider.java` — serves it through `openPipeHelper` (real OS pipe), authority `${applicationId}.zip64`,
  registered in `composeApp/src/androidInstrumentedTest/AndroidManifest.xml`. `PipeZipTestProvider.java` untouched.
- `composeApp/src/androidInstrumentedTest/kotlin/com/najdev/snapvault/Zip64PipeDocumentTest.kt` — four cases, 10-minute timeout each:
  1. raw pipe scan keeping only head/tail/28-byte window: checks the ZIP64 structure (flags, 0xFFFFFFFF escapes, extra
     field size == 4 GiB+, descriptor sizes/CRC match the directory, layout adds up to the byte, locator/end record);
  2. production `listZipEntries` over the pipe returns all three names;
  3. `readZipEntryText` finds history after the huge entry; missing entry is null;
  4. production `StreamingZipReader.forEachEntry` reads the huge entry to the exact byte count and CRC32 (recomputed
     from the pattern) and the small entries byte-for-byte.
  Cases 2–4 also assert `AndroidDocumentAccess.directory` is null for the pipe and that cacheDir/filesDir are unchanged.
- The APK built with `ANDROID_HOME=/opt/android-sdk ./gradlew -I /tmp/snapvault-android-test.init.gradle :composeApp:assembleDebugAndroidTest`
  succeeded; test APK sha256 `3eed0f2ac535cd3d58b9675399a7ee66fb8464998ba63838a5c9177c58bacc21`; main debug APK sha256 `1b1e6adaa8c87922337591ea962c85ec01d13afc87b35bbcd008653af2b909a7`
  (equals the golden installed on the QA emulator on 2026-11-08, so no app reinstall would be needed).

## Host-JDK evidence actually obtained (NOT Android; supplementary)

- `zip64-host-python-zipfile.log`: the fixture, generated in 2.6 s on a host JDK, parses with Python `zipfile` (ZIP64 directory),
  passes `testzip()` (CRCs) and `unzip -tq` — independent confirmation the bytes are a valid ZIP64 archive.
- `zip64-host-production-reader.log`: the compiled production `StreamingZipReader` (desktop classes) over a piped fixture:
  `entries()` lists all 3 entries, `readText` finds history / null for missing, `forEachEntry` reads 4 294 979 641 bytes with
  CRC `d95554fb` (same as Python). 20 s for four full passes. This is host `java.util.zip.ZipInputStream`; Android/libcore results are recorded separately above.
- `zip64-host-mutant-32bit-descriptor.log`: controlled fixture mutation (descriptor rewritten with 4-byte sizes) makes the
  production reader fail with `ZipException: invalid entry size`. Python `testzip()` still returns None for that mutant, which is why
  test case 1 checks the streamed descriptor itself and not only the directory.

## Scope

Synthetic, deterministic and compressible; says nothing about a real cloud provider, real Snapchat exports, other ZIP64 shapes
(e.g. >4 GiB compressed, offsets past 4 GiB, ZIP64-only counts) or publishing >4 GiB output. Real cloud behaviour is unverified.

## Follow-up attempt (same day, later session) — still NOT run on a device

- `device_list` reported `christian-mac` ready and `emulator-5556` (SnapVault_QA_t3_24e5c88e) booted; `device_open` bound it.
- Plan review (`agent-verify --plan`) passed. The test APK rebuilt locally (BUILD SUCCESSFUL, 12 s) with sha256
  `3eed0f2a…cc21`, identical to the earlier build; main debug APK sha256 still `1b1e6ada…09a7` (golden).
- Blocker: this devbox has no usable route to run `adb install`/`am instrument` on the Mac. `ssh najchris11@<mac tailscale ip>`
  with the default key returned `Permission denied (publickey,password,keyboard-interactive)`; the bound `agent-device` CLI offers
  `install` but no instrumentation runner. Stopped without retry loops. No install, no test run, no device claim, no source
  changes (Zip64Fixture.java verified byte-identical to its backup).
