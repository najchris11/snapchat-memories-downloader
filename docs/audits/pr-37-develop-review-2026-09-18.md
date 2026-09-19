# PR #37 review against develop — 2026-09-18

Scope: desktop remediation branch, starting at `664d93a`, against develop `7b0cfa2`.
This is a code and automated usability review for integration into develop, not a
certification of packaged releases on every operating system.

## Findings addressed in this review

1. **High: legacy ZIP cleanup could discard an unextracted memory.** A nonempty
   destination caused `extractEntry` to return `skipped`; legacy cleanup treated that
   as successful extraction and deleted the archive even when its bytes differed.
   Legacy cleanup now retains the archive and warns whenever any destination is
   skipped. Such destinations are not returned as freshly extracted files for the
   metadata pass. Normal fresh extraction still removes a fully extracted archive.
   The regression uses different, same-length content and asserts both copies survive.
   It was run against the old implementation and failed on archive retention.

2. **Usability: combine hid its cleanup consequences and photo scope.** The previous
   label was “Merge Video Overlays”, although the operation also combines photos and
   permanently removes successfully processed source pairs. The maintainer explicitly
   confirmed during this review that automatic cleanup should remain. The Dashboard
   now names both media types and explains deletion while combine is enabled. A UI
   regression checks the disclosure before import and its removal when combine is off.

3. **Documentation overstated D20 availability.** Low-space source-ZIP deletion is
   disabled in production by `LOW_SPACE_DELETE_ENABLED = false`. The remediation plan
   now explicitly supersedes its older “landed” status. Do not re-enable on the strength
   of the existing size-only verification tests.

## Review coverage

- Extraction scope, existing-file handling, staging, archive cleanup, and low-space gate.
- Download resume, streaming/stall bounds, repeated-row handling, and destination checks.
- Combine staging, output conflicts, metadata-copy gate, and derivative index updates.
- Favorite persistence, corrupt-index write refusal, dedupe filtering, run locks and close flow.
- Tool cache migration, bundled-tool tests, thumbnail refresh, support-link failure handling,
  source-cleanup/GPS copy, disabled controls, and native macOS window configuration.
- Release version tests and workflow ordering: builds precede publishing; manual release
  dispatch remains separate from merging develop.

## Remaining limitations and release follow-up

- **D20 remains disabled.** Equal size is not content identity; unrecognized archive entries,
  history-backup failure, deletion before later processing, and crash recovery need a complete
  ownership/commit contract before permanent source-ZIP cleanup can be offered.
- **Resume still trusts nonempty existing media.** It does not prove source identity against
  a completion manifest. This review prevents the legacy archive-deletion consequence;
  skipped files can still represent a different import. Keep this visible as deferred D14.
- **Cross-process locking covers pipeline runs, not every index mutation.** Favorites and
  badge reset use an in-process mutex. Two app processes editing the same library can still
  race those writes; a corrupt index also reads as empty for read-only consumers, including
  the favorites snapshot used by dedupe. Extend the write/cleanup coordination before
  claiming general multi-process library safety. These paths predate this review's fixes.
- **Resource estimates are approximate.** The ordinary ZIP path uses declared sizes plus a
  fixed reserve; it does not enforce a runtime extraction-byte or image-pixel quota. Another
  application can consume free space after preflight. Very large inputs and image decoding
  remain resource-hardening work, as already recorded under D13.
- Clean-machine installer tests, Windows runtime-script execution, packaged AeroSpace and
  accessibility checks, signing/notarization, and release-workflow success/failure dry runs
  remain manual release gates. They were not performed in this review. No release is implied
  by merging this PR into develop.

## Validation

- Full desktop suite: **393 tests, zero failures/errors/skips**.
- Desktop, Android debug, and iOS simulator Kotlin compilation: passed.
- Detekt: passed. Release-script unit tests: **9 passed**. `git diff --check`: passed.
- Both new regressions failed before their fixes. The disclosure was also deliberately
  removed after the UI test passed; the test failed again, then passed with it restored.
- Suitable for integration into develop with the limitations above retained as follow-up.
  GitHub checks on the final pushed commit must pass before the requested merge.
