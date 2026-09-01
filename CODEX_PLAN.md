# Mobile integration verification plan

## Current assessment

- Remote `main` includes PR #25, the iOS/mobile Phase 3 implementation. This was a squash merge, not a partial merge: its file tree is identical to `mobile/pr-g-ios-pipeline-and-pickers`.
- Desktop tests pass and the iOS simulator Kotlin target compiles. (Last verified 2026-09-01, after `fix/desktop-audit-fixes`: 77/77 — see `FIX_PLAN.md`. `.github/workflows/check.yml` was split into a required `desktop` job and an informational `mobile-preview` job, `continue-on-error: true`, since it's expected to fail until item 2 below — Android SDK provisioning — is actually done.)
- Android assembly has not been verified on this machine because the Android SDK is not configured.
- Do **not** remove or revert the mobile code as a cleanup action without a product decision; it is the current contents of `main`.
- Mobile is currently shelved as a non-priority future release — desktop is the active release target. The work items below remain the right plan for when mobile picks back up; they are not being executed now.

## Work items

1. Refresh the local baseline without overwriting work.
   - Fetch `origin`; fast-forward the local `main` to `origin/main` when its working tree is clean.
   - Confirm the active mobile branch still has no content diff from `origin/main` before deciding whether to retire it.

2. Establish platform build verification.
   - Configure a valid Android SDK through local environment/local properties (never commit a machine-specific path).
   - Run `./gradlew :composeApp:assembleDebug`, `./gradlew :composeApp:desktopTest`, and `./gradlew :composeApp:compileKotlinIosSimulatorArm64`.
   - Build and launch the iOS simulator app once with the documented Xcode/Gradle workflow; exercise HTML, output-folder, and ZIP pickers.

3. Cover the mobile regression surface.
   - Add tests for picker callback lifetime, distinct output/ZIP folder selection, security-scoped resource cleanup, ZIP extraction progress (including overlays), GIF preservation, and atomic metadata replacement.
   - Keep filesystem/metadata behavior behind testable interfaces; use platform integration tests only for platform APIs.

4. Protect `main` going forward.
   - Add CI jobs that compile Android and iOS alongside the existing desktop test job, using supported runners and SDK setup.
   - Require those checks for mobile-affecting pull requests.

5. Close out deliberately.
   - Record the platform results and remaining device-only checks in the PR.
   - Only then archive/delete superseded mobile branches through the normal review process.

## Acceptance criteria

- All three Gradle validations succeed in a configured environment.
- Automated coverage exists for the listed regressions.
- CI blocks merges when Android/iOS compilation fails.
- `main` and the intended release branch are documented and current.
