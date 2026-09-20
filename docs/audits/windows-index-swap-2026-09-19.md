# Windows: the index swap, and a read that reports empty — 2026-09-19

Both halves are now fixed and on `develop`. See "Resolved — 2026-09-20" below for the read-side
fix; everything else in this document is preserved as the diagnostic record that led to it.

Handoff for debugging on a real Windows machine. Everything through "What happened, in order"
below was observed on GitHub's `windows-latest` runner, which was the only Windows this had run
on at the time — `check.yml` builds on Linux only, so no PR had exercised these paths on
Windows until the reproduction described below.

## Status

- `main` is at `0465dbe`. `app.version` is still `1.0.13`; **no `v1.0.14` tag or release exists.**
- The release workflow's gate held correctly every time: `publish` is skipped unless every
  platform's build succeeds, so a failing Windows job published nothing.
- macOS arm64, macOS x64 and Linux x64 have passed tests, packaged and verified on every run.

## What happened, in order

| Run | Ref | Windows result |
|---|---|---|
| [35459645360](https://github.com/najchris11/snapchat-memories-downloader/actions/runs/35459645360) | `main` (pre-fix) | **failed** — 2 tests: `AccessDeniedException` + `OutputDirectoryInUseException` |
| [35460628869](https://github.com/najchris11/snapchat-memories-downloader/actions/runs/35460628869) | `develop` (post-fix) | **passed** — all four platforms green |
| [35461655578](https://github.com/najchris11/snapchat-memories-downloader/actions/runs/35461655578) | `main` (same content) | **failed** — 1 test: `AssertionError` at `VaultIndexConcurrencyTest.kt:111` |

The second and third runs had **identical content**. The middle run passing was a race landing
favourably, not evidence the bug was gone. Treat any single green Windows run as weak evidence
for this test — it needs repeating before it means anything.

## Half one — fixed, on `develop` (`fe502ee`)

`VaultIndex.writeAtomically` writes a temp file and `atomicMove`s it over the index, so a
reader can never see a partial file. Windows refuses to replace a file another handle has
open, so the move threw `AccessDeniedException` whenever a read overlapped it, and the write
failed — losing the favorite the user had just pressed. POSIX `rename(2)` ignores open
handles, which is why this never appeared on macOS or Linux.

`VaultIndex.moveIntoPlaceRetrying` (`VaultIndex.kt:159`) now retries on `IOException` with
bounded backoff (10 attempts, ~0.5s) and rethrows the last failure unchanged. Covered
portably by `VaultIndexAtomicMoveRetryTest`, which drives the failure side through a
`ForwardingFileSystem` rather than needing a Windows runner.

This half is believed correct — the `AccessDeniedException` has not recurred.

## Half two — open, and the actual blocker

Fixing the write side let the test run to completion, which exposed the read side:

```
VaultIndexConcurrencyTest > aReadDuringAWriteNeverObservesAPartiallyWrittenIndex FAILED
    java.lang.AssertionError at VaultIndexConcurrencyTest.kt:111
```

The failing assertion is `empties.get() == 0` — *"a read saw a half-written index and silently
turned it into an empty one"* — under 16 concurrent writers and 16 readers doing 50 reads each.

The suspect is `VaultIndex.read` (`VaultIndex.kt:97`):

```kotlin
fun read(fileSystem: FileSystem, folder: String): Map<String, FileMeta> = runCatching {
    json.decodeFromString<Map<String, FileMeta>>(fileSystem.read(path(folder)) { readUtf8() })
}.getOrDefault(emptyMap())
```

`runCatching` swallows everything. Its doc comment says this is total *by design*: the Library
scan is not a coroutine and has nowhere to put a failure, and for a reader "the facts are
unknown" and "there are no facts" lead to the same screen. That reasoning holds on POSIX. On
Windows it conflates a transient sharing error during the replace with genuine absence.

**User-facing consequence if confirmed:** on Windows the Library can transiently show zero
favorites, and any caller treating `read()` as truth acts on an empty index.

### Two hypotheses, not yet distinguished

1. **The reader hits a transient error.** Opening the target while `MoveFileEx` replaces it
   throws `AccessDeniedException` / a sharing violation, which `runCatching` turns into
   `emptyMap()`.
2. **The reader genuinely sees no file.** If `Files.move(..., ATOMIC_MOVE)` on Windows is not
   in fact atomic here — or Java falls back to a delete-then-rename path — there is a real
   window with no target file, and `emptyMap()` is a truthful answer to a broken swap.

These need different fixes. (1) is fixed in `read`; (2) is fixed in the swap and is more
serious. **Do not write a fix before establishing which.**

### How to tell them apart on Windows

Run the single test in a loop and log what `runCatching` is actually catching:

```
./gradlew :composeApp:desktopTest --tests "com.najdev.snapvault.VaultIndexConcurrencyTest" --rerun-tasks
```

Temporarily replace `getOrDefault(emptyMap())` in `read` with an `onFailure` that prints the
exception class and message before returning empty, then:

- **Hypothesis 1** if the throwable is `AccessDeniedException` or a sharing violation.
- **Hypothesis 2** if it is `FileNotFoundException` / `NoSuchFileException`, or if there is no
  throwable at all and the file parsed as an empty object — which would mean a torn read that
  produced valid-but-empty JSON, a third and worse case.

Expect it to reproduce intermittently. Loop it; a single pass means nothing here.

### What a fix must preserve

- `read` stays total — the Library scan has nowhere to put a failure. Absence must still mean
  `emptyMap()`.
- A transient failure must **not** be reported as absence. Retry it the way the write side now
  does, or give the caller a way to tell the two apart.
- Do not replace the temp-file swap with a copy onto the target. The temp file exists to stop
  the torn read this test is about.
- The regression must be provable off Windows — drive the failure through a
  `ForwardingFileSystem`, as `VaultIndexAtomicMoveRetryTest` does — or CI will never see it.

## Also worth knowing

`FileOutputDirectoryLockerTest.theDirectoryIsClaimableAgainOnceTheOtherProcessExits` failed on
the first run too, and now retries for up to 10s (`a39acf5`). That was a test-shape problem:
Windows signals the process object before it has finished tearing the file handle down. **The
production locker was left unchanged** — it is correct, and its refusal carries the right
message. A user who force-quits and relaunches within milliseconds on Windows can still see
"already being updated" once. Minor, but real, and not worth weakening the guard for.

## Release follow-up

- Decide whether v1.0.14 ships all four platforms or holds Windows back **deliberately**
  rather than by a failing job.
- `check.yml` runs Linux only. Two real Windows defects have now surfaced in code that passed
  every PR. Consider adding a Windows test job, at least for the `VaultIndex` and locker
  suites — the release workflow is currently the only thing that runs them on Windows, which
  is far too late to find out.

## Resolved — 2026-09-20

Diagnosed and fixed on real Windows hardware (not `windows-latest`), which made looping the
failing test to reproduction practical for the first time.

**Hypothesis 1 confirmed, hypothesis 2 ruled out.** Instrumenting `read()`'s `onFailure` and
looping `VaultIndexConcurrencyTest` reproduced the failure at roughly the same 1-in-15–20 rate
implied by the CI history above. The caught exception was consistently:

```text
java.io.FileNotFoundException: ...\vault_index.json (The process cannot access the file
because it is being used by another process)
```

That message is a Windows sharing violation, not a genuine absence — hypothesis 1. No case
produced hypothesis 2 (`NoSuchFileException`) or the third, worse case (a torn read parsing to
valid-but-empty JSON). The swap itself was never the problem; only the reader's own retry
behavior was missing.

**Fix:** `VaultIndex.read` (`VaultIndex.kt:117`) now retries on `IOException` while
`FileSystem.exists(target)` still says the file is there, on the same bounded backoff
(`READ_ATTEMPTS`/`INITIAL_READ_BACKOFF_MILLIS`/`MAX_READ_BACKOFF_MILLIS`, mirroring
`MOVE_ATTEMPTS` for the write side) rather than by matching the exception message. If the file
is genuinely gone, the first attempt still returns `emptyMap()` immediately — the ordinary
first-run path gets no added latency. `read` stays a plain, non-suspend function (Library scans
still aren't coroutines); the backoff uses `runBlocking { delay(...) }` rather than
`Thread.sleep`, since this file is `commonMain` and `java.lang.Thread` isn't available on the
iOS target.

**Test:** `VaultIndexReadRetryTest` drives the failure through a `ForwardingFileSystem` that
throws the exact exception above on the first N opens of the index, so this is provable without
Windows hardware and runs in `check.yml`. Confirmed failing against the pre-fix `read()` (2 of 4
cases failed on the retry assertion, not a compile error) before restoring the fix.

**CI evidence:** `PR Check` on `develop` passed (Linux). A `dry_run` `Release` workflow run
(`35541042394`) then passed on all four platforms, including `build (windows-x64)` running the
full desktop test suite. Per the "one green run" caution earlier in this document, one clean
Windows CI pass is supporting evidence, not proof the intermittent case is gone at CI's single-
run-per-push cadence — but the fix targets the confirmed mechanism (the specific transient
exception is now retried) rather than a timing change that could get lucky, which is the
material difference from the earlier false-green run described above.

Commit: `d209622` on `develop`. Not yet merged to `main`; the v1.0.14 release-follow-up
decision above is still open.
