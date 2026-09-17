# Desktop installation, signing, and distribution roadmap

Updated: 2026-09-16. Desktop only. This is a setup plan, not a claim that the latest
installers have passed release acceptance testing.

## Budget and immediate priority

The current budget is zero. Prioritize a tested release through the existing download
channel, with accurate installation instructions. Do not make it depend on a paid
certificate, account approval, store review, or a new packaging format. None of those
has a dependable same-day turnaround.

The repository currently targets DMG, MSI, and DEB, without signing configuration in
`composeApp/build.gradle.kts`. The existing workflow publishes GitHub release assets.
It packages a Java runtime; users should not need to install Java or Gradle.
macOS/Windows bundle media tools; Linux currently requires system FFmpeg and Perl for
the bundled ExifTool. Verify these expectations against the actual installers.

| Platform | Immediate path, no signing purchase | Later improvement |
|---|---|---|
| macOS | Existing unsigned DMG; explain the app-specific opening exception | Developer ID signing, notarization, and stapling |
| Windows | Existing unsigned MSI; disclose possible SmartScreen warnings | Free Store MSIX distribution, or paid signing for direct downloads |
| Linux | Existing DEB; explain supported distributions and dependencies | Declare dependencies, then investigate Flathub |

Unsigned releases have installation friction that cannot be completely removed at zero
cost on macOS. A checksum or self-signed certificate does not replace public-trust
signing or notarization. Do not describe unsigned builds as approved by Apple/Microsoft.

## Snapchat deadline: communicate verified facts

As checked on 2026-09-16, Snapchat's official support page says declining a storage plan
does not automatically delete Memories. It says excess Memories will begin being
archived in January 2027, with no archiving before then and advance notice in the app.
Archived content remains stored; access to open/edit/share requires an upgrade, with
downloading offered as an alternative. This does **not** establish a general deletion
deadline this weekend. Recheck before making time-sensitive public claims, and direct
users to notices in their own Snapchat account.

Source: [Snapchat Memories storage guidance](https://help.snapchat.com/hc/en-us/articles/41291271694228-How-do-I-manage-my-Memories-storage).

Keep the practical message: export and verify your own backup. SnapVault's release,
signing status, or tip page should not be presented as a reason to panic or delete the
original export. Processing an export and requesting it from Snapchat are separate steps.

## Immediate release checklist

These are proposed tasks; this document does not mark them completed.

- [ ] Choose the exact release commit, reconcile its version, and run required checks.
- [ ] Revisit the audit fixes before claiming the release is safe; signing is not a code audit.
- [ ] Download the packaged artifacts through the actual public download channel and
  install on clean machines without developer tools. Run a small complete import.
- [ ] Test upgrading over the previous release and uninstalling. Preserve user media
  and clearly explain any application settings/cache removal.
- [ ] Label assets by OS and CPU architecture; test each architecture advertised.
  One macOS installer is not automatically universal. State minimum tested OS versions.
- [ ] Publish SHA-256 checksums for integrity troubleshooting. They do not suppress OS
  warnings or independently prove publisher identity.
- [ ] Provide obvious platform download links, installation screenshots, limitations,
  version, and support contact. A GitHub release page is sufficient initially;
  a paid website is unnecessary.
- [ ] Explain source selection, output folder, disk needs, and deletion options.
  Encourage keeping the export until the imported library is verified.
- [ ] Confirm the packaged build has no debug import cap and no missing runtime tools.
- [ ] Keep unsigned instructions while any offered artifact remains unsigned.

Do not publish a partial set as though every advertised platform is available. Signing,
store distribution, and a support button can follow an otherwise ready release.

## macOS now: unsigned DMG

Install into Applications, then try opening SnapVault. For an unidentified-developer or
unnotarized-app warning, explain Apple's app-specific route: **System Settings →
Privacy & Security → Open Anyway**, then confirm opening. Availability varies, including
on managed Macs. Include a screenshot from a supported macOS version.

Do not tell users to disable Gatekeeper globally. Do not use the same workaround for
explicit malware, revoked-signature, or damaged-app warnings: investigate the artifact.
Remove the old blanket quarantine-removal recommendation from public quick-start
instructions when updating them.

Source: [Apple: safely open apps on your Mac](https://support.apple.com/en-us/102445).

## Windows now: unsigned MSI

Explain before download that Windows may show an unknown-publisher or SmartScreen
warning. Where offered, **More info → Run anyway** is an individual user decision for
the expected download. Some policies can block unsigned/unrecognized apps; do not
promise this option is always available or tell users to disable antivirus.

Improve the installer independently of signing:

- Configure `perUserInstall = true` if compatible with the app's needs; verify installation
  without elevation for a standard Windows user.
- Set one stable `upgradeUuid` across releases. Test upgrading an existing installation,
  especially when introducing this setting to already released packages.
- Add sensible Start menu integration and consistent publisher/application names.
- Preserve media, favorites, and settings through upgrades. Verify paths with spaces
  and non-ASCII characters and installation without a preinstalled JVM.

Compose exposes these settings. Source:
[Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html).

## Linux now: existing DEB

Call this a Debian/Ubuntu package, not an installer for every Linux distribution.
Explain FFmpeg and Perl requirements. If needed on a supported Debian/Ubuntu system,
the fallback instructions are:

```sh
sudo apt install ffmpeg perl
sudo apt install ./SnapVault-<version>.deb
```

Replace the filename with the downloaded asset. APT installation of a local package can
resolve declared dependencies; plain `dpkg -i` does not fetch them. The packaging
improvement is to declare dependencies so users do not need the separate first command.
Verify the resulting DEB metadata and installation on supported distribution versions.

For later package-manager updates, host an APT repository and sign its Release metadata
with a GPG key scoped through `signed-by`. A downloaded DEB does not gain Windows-style
publisher verification just by existing, and an unsigned download still has trust risks.

Sources: [APT installation](https://www.debian.org/doc/manuals/apt-guide/ch2.en.html),
[Debian package authentication](https://www.debian.org/doc/manuals/debian-handbook/sect.package-authentication.en.html).

## Later: free Microsoft Store route

Explore this before buying Windows signing. Store **MSIX** packages receive
Microsoft-managed signing. Store MSI/EXE submissions still require publisher signing;
merely listing the current MSI does not solve that cost.

Sources: [Microsoft signing options](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options),
[Windows packaging overview](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/packaging/).

Setup sequence:

1. Start at [storedeveloper.microsoft.com](https://storedeveloper.microsoft.com/).
   Current onboarding has no registration fee for Individual or Company accounts.
2. Read eligibility descriptions and choose the correct account for the publishing
   activity. Do not assume a commercial project qualifies for hobbyist onboarding.
   Individual accounts cannot be converted to Company accounts.
3. Complete identity verification, reserve the app name, and prepare screenshots,
   description, support contact, and privacy information.
4. Trial MSIX conversion using Microsoft's Packaging Tool. Compose's current MSI
   configuration is not itself an MSIX implementation.
5. Test selected folders, Java/native library loading, and media-tool execution.
   Package installation files are read-only; writable data needs an appropriate user
   location. Keep user media outside disposable package data.
6. Test upgrade/uninstall and submit for certification. MSIX Store delivery supports
   automatic updates; Store MSI delivery does not provide the same update service.

Sources: [Publisher account setup](https://learn.microsoft.com/en-us/windows/apps/publish/faq/open-developer-account),
[MSIX runtime behavior](https://learn.microsoft.com/en-us/windows/msix/msix-containerization-overview).

Engineering and review take time even though registration is free. Keep the current
release independent. Review Store policy before including external tipping links;
the direct desktop design is not automatic approval for a future Store package.

## Later: paid Windows signing, only when affordable

Azure **Artifact Signing** is the current name of former Trusted Signing. Microsoft
lists the entry price at approximately US$9.99/month. OV certificates are another paid
route. Neither cloud signing nor EV guarantees immediate SmartScreen trust; EV no longer
has an automatic bypass. Do not buy EV solely to avoid SmartScreen warnings. Self-signed
certificates are for testing, not public distribution.

Source: [Microsoft signing options](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options).

If choosing Azure later:

1. Check current public-trust eligibility. Individuals currently must be in the US or
   Canada; organizations have a broader supported-country list.
2. Create an Azure subscription and Entra tenant; register `Microsoft.CodeSigning`.
3. Create a Basic Artifact Signing account, complete public identity validation, and
   create a **Public Trust** certificate profile. Individual billing details must match
   identification. Do not use a Private Trust or test profile for public releases.
4. Connect the release workflow with OIDC/federated credentials rather than a long-lived
   secret. Grant **Artifact Signing Certificate Profile Signer** on the profile.
5. Sign SnapVault's executable before building the installer, then sign the final MSI.
   Preserve valid third-party signatures; handle redistributed native files deliberately.
   Timestamp signatures and verify the packaged result before uploading.

Record tenant ID, client ID, account name, profile name, and regional endpoint for CI.
Pin the official signing action to a reviewed commit when integrating it.

Sources: [Azure setup](https://learn.microsoft.com/en-us/azure/artifact-signing/quickstart),
[Signer roles](https://learn.microsoft.com/en-us/azure/artifact-signing/tutorial-assign-roles),
[Official GitHub Action and OIDC setup](https://github.com/Azure/artifact-signing-action).

Identity approval has no dependable same-day completion. Do not start a recurring paid
service just to meet the proposed weekend timeline.

## Later: macOS signing and notarization

When affordable, enroll in the [Apple Developer Program](https://developer.apple.com/programs/)
(normally US$99/year; check local pricing). Use **Developer ID Application** for this
outside-the-Store app, not an App Store distribution certificate. Account activation is
separate from submitting a built artifact to notarization.

Implementation plan:

1. Create/import the Developer ID certificate and private key. Store CI credentials
   securely; use a temporary build keychain and clean it up afterward.
2. Configure Compose's signing/notarization options for the repo's plugin version.
   Verify task names and configuration against its documentation before integration.
3. Sign required nested native code and helper executables before sealing the app;
   configure hardened runtime, secure timestamps, and only necessary JVM entitlements.
   Inspect compressed helper payloads as well as visibly nested executables.
4. Package, submit with `notarytool`, wait for acceptance, review the log, and staple
   the ticket to the distributed DMG/app as appropriate. Verify stapling explicitly.
5. Test the final browser-downloaded artifact on a clean Mac, including media tools
   after extraction, every shipped architecture, and offline first launch.

Potential CI secrets: `MACOS_CERT_P12_BASE64`, `MACOS_CERT_PASSWORD`, `APPLE_ID`,
`APPLE_APP_PASSWORD`, `APPLE_TEAM_ID`. API-key authentication is another option where
supported. Keep secrets out of Gradle files, logs, and source control.

The previous guide asserted that extracted helpers necessarily inherit quarantine and
suggested automatically removing it. That was too broad. Inspect actual artifacts and
runtime behavior, correctly sign/distribute helpers, and do not build a security bypass
into the extractor as a substitute for proper distribution.

Sources: [Apple notarization](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution),
[Resolving notarization issues](https://developer.apple.com/documentation/security/resolving-common-notarization-issues),
[Compose distribution configuration](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html).

## Later: Flatpak / Flathub

Prepare a Flatpak manifest, desktop entry, MetaInfo, icons, and reproducible dependency
sources. Include needed Java/media dependencies in the package environment rather than
expecting host FFmpeg. Test native libraries, rendering, selected folders, and opening
the browser/file manager from the sandbox. Use portals and minimal permissions.

Review and sandbox integration are not a same-day substitute for the DEB. Flathub's
current policy requires disclosure of AI-generated material and human handling of
submission/review interactions; the maintainer should handle those steps directly.

Sources: [Flathub requirements](https://docs.flathub.org/docs/for-app-authors/requirements),
[Submission procedure](https://docs.flathub.org/docs/for-app-authors/submission).

## Optional support link

Use a free Ko-fi page with one-time tips if it suits the maintainer's payment-provider
availability. Do not delay the release while setting it up. Comparison, setup, proposed
desktop placement, and required regression tests are in
[SUPPORT_AND_TIPPING.md](SUPPORT_AND_TIPPING.md).
