# Spec: Android video overlay burn-in

Android currently skips every video/overlay pair (`AndroidMediaProcessor.combineVideoWithOverlay` returns false; `StagedImageOverlayCombiner` reports `SkippedVideo`). Desktop (FFmpeg) and iOS (AVFoundation) burn the overlay in.

Acceptance criteria
- c01: `StagedImageOverlayCombiner` accepts an optional suspend video encoder `(main, overlay, stagedOutput) -> Boolean`. Without one, behaviour is unchanged (videos -> SkippedVideo). With one, `.mp4`/`.m4v` video pairs are encoded into `.snapvault-staging`, published with the same non-overwriting publish, and reported Combined. Other video containers (e.g. `.mov`) stay SkippedVideo on Android (Media3 muxes MP4 only; no disguised bytes).
- c02: Video originals are NEVER deleted, even with deleteOriginals=true, and a combined video is reported with metadataCarried=false via the shared marker (same as iOS: metadata fidelity unverified). Combined videos are excluded from the EXIF date fallback (ExifInterface cannot write MP4).
- c03: Encoder failure/exception -> Failed, staged file removed, originals intact; existing output -> SkippedExistingOutput without encoding; cancellation propagates and leaves no staged file and no output.
- c04: Android implementation uses Media3 Transformer with a static bitmap overlay scaled to the displayed frame (rotation aware), keeps audio, runs on a Looper thread, and cancels the transformer on coroutine cancellation, waiting for it to stop before staging cleanup.
- c05: User-facing copy no longer says Android leaves video overlays separate; strings in strings.xml; Compose/resource tests updated (StringResourceHygieneTest passes).
- c06: TDD: desktopTest cases for c01–c03 written first and seen failing. Native instrumentation test on the QA emulator for the real exporter (output exists, has video+audio track, expected display dimensions, overlay pixels present in a decoded frame, sources retained, cancellation leaves no output), with a deliberate mutant proving it can fail.
- c07: detekt clean (no baseline additions), full desktopTest, three-target compile, evidence + checklist/progress/mobile-plan docs updated only from actual evidence. No commit.
