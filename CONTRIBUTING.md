# Contributing to SnapVault

Thank you for your interest in contributing to SnapVault!

## Prerequisites

- **JDK 21** (JetBrains Runtime 21 recommended when building native desktop distributions)
- **Linux runtime tools (for running from source on Linux):** `ffmpeg` and `perl`, plus `bash scripts/prepare-runtime-linux.sh` once if you want bundled ExifTool under `composeApp/src/desktopMain/resources/bin/linux-x64/`.
- **Android SDK** (optional, required only for `:composeApp:compileDebugKotlinAndroid` or running the Android target).

## Verification Before Opening a Pull Request

Every behavioral change or bug fix must include a regression test that fails without the change and passes with it. Run all applicable checks locally before submitting:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
./gradlew detekt
./gradlew :composeApp:desktopTest
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:compileDebugKotlinAndroid
./gradlew :composeApp:compileKotlinIosSimulatorArm64
```

## Coding & UI Conventions

- **Strings:** All user-facing UI text and `contentDescription` values belong in `composeApp/src/commonMain/composeResources/values/strings.xml` (`StringResourceHygieneTest` fails on hardcoded UI strings or unused resource keys).
- **Theming:** Read colors from `MaterialTheme.colorScheme`, `SnapVaultColors`, or `LogColors` rather than raw palette constants so both light and dark themes stay legible.
- **Static analysis:** Do not add entries to `config/detekt/baseline.xml` to suppress new findings.
- **Commits:** Explain *why* a change was made in the commit message. Note that `.githooks/commit-msg` (installed automatically on build) rejects AI attribution trailers and footers.

## License

By contributing to SnapVault, you agree that your contributions will be licensed under the [GNU General Public License v3.0 or later](LICENSE).
