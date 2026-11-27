# Winget manifests (candidate, v1.0.15)

**Status: prepared, not published.** Nothing here has been submitted to `microsoft/winget-pkgs`,
run through `winget validate`, or installed on Windows. No Windows, winget, Wine or PowerShell
was available when this was prepared.

The manifests describe the **already-published** v1.0.15 release, not the working tree. In
particular they do not describe unreleased behaviour: the published MSI is a **machine-scope**
installer (`ALLUSERS=1`), so the manifest says `Scope: machine`.

## Layout

```
distribution/winget/
  evidence.json                 facts about the MSI, release and pinned schemas
  validate_manifests.py         static checks + negative control (needs jsonschema, pyyaml)
  manifests/n/NajDev/SnapVault/1.0.15/
    NajDev.SnapVault.yaml               version
    NajDev.SnapVault.installer.yaml     installer
    NajDev.SnapVault.locale.en-US.yaml  default locale
```

The folder layout mirrors winget-pkgs (`manifests/<initial>/<Publisher>/<Package>/<version>/`), so
the version folder can be copied as-is into a fork. Multi-file form, because winget-pkgs
deprecates singleton manifests.

## Evidence

The installer is `SnapVault-1.0.15-windows-x64.msi`, 225,452,096 bytes, SHA-256
`98ab1361d36ee455a69411ce2b40d3d1f81f201c02a1f5a2ee6b2fb69e0d370a`. That hash agrees across the
downloaded file, the GitHub release asset digest and the release's `SHA256SUMS.txt`.

Read from the MSI itself with msitools 0.106 (`msiinfo export <msi> Property`, `msiinfo suminfo`):

| MSI property | Value | Manifest field |
|---|---|---|
| ProductName | SnapVault | `PackageName`, ARP `DisplayName` |
| ProductVersion | 1.0.15 | `PackageVersion`, ARP `DisplayVersion` |
| Manufacturer | Unknown | ARP `Publisher` |
| ProductCode | `{FDF695BB-26FD-3E6F-B3A1-6071E297B937}` | `ProductCode` (twice) |
| UpgradeCode | `{6249E2F0-8A8F-3FDB-BCD8-F554C94B1090}` | ARP `UpgradeCode` |
| ALLUSERS | 1 | `Scope: machine` |
| Template | `x64;1033` | `Architecture: x64` |
| Application | Windows Installer XML Toolset 3.11.2.4516 | `InstallerType: wix` |

Release published 2026-10-06T18:55:31Z, tag commit `e91d545`. Descriptive text, the license
(`GPL-3.0-or-later`) and the Windows 10+ requirement come from the `v1.0.15` README, not the
current working tree. `evidence.json` records all of this.

## Schema

Manifest version **1.12.0**, the version winget-pkgs' own `Tools/YamlCreate.ps1` emits by default.
Schemas are fetched from `microsoft/winget-cli` at commit `afc9a471496c`, and the script refuses to
run if a SHA-256 differs from `evidence.json`. As a cross-check the manifests are also validated
against the 1.28.0 schema, the newest in that repository. The 1.28 client does not support every
1.28 field, so the manifests stay at 1.12.0.

## Run the static checks

```
python3 -m venv /tmp/wgvenv && /tmp/wgvenv/bin/pip install jsonschema pyyaml
/tmp/wgvenv/bin/python distribution/winget/validate_manifests.py --msi /path/to/SnapVault-1.0.15-windows-x64.msi
```

The script is not named `test_*.py` on purpose, so `python3 -m unittest discover -s scripts`
keeps running on the standard library alone. Output when prepared (jsonschema 4.26.0,
PyYAML 6.0.3):

```
MSI size+sha256 match evidence: True
schema 1.12.0: OK
schema 1.28.0: OK
consistency with MSI evidence: OK
negative control (each mutation must be rejected):
  rejected: malformed InstallerSha256  -> installer: Installers/0/InstallerSha256: 'not-a-hash' does not match '^[A-Fa-f0-9]{64}$'
  rejected: unknown Scope  -> installer: Scope: 'everyone' is not one of ['user', 'machine']
  rejected: no Installers  -> installer: : 'Installers' is a required property
  rejected: unknown InstallerType  -> installer: InstallerType: 'rpm' is not one of ['msix', 'msi', 'appx', 'exe', 'zip', 'inno', 'nullsoft', 'wix', 'burn', 'pwa', 'portable', 'font']
  rejected: PackageIdentifier without publisher  -> version: PackageIdentifier: 'SnapVault' does not match '^[^\\.\\s\\\\/:\\*\\?"<>\\|\\x01-\\x1f]{1,32}(\\.[^\\.\\s\\\\/:\\*\\?"<>\\|\\x01-\\x1f]{1,32}){1,7}$'
  rejected: valid-looking wrong sha  -> InstallerSha256: '0000000000000000000000000000000000000000000000000000000000000000' != '98ab1361d36ee455a69411ce2b40d3d1f81f201c02a1f5a2ee6b2fb69e0d370a'
  rejected: wrong ProductCode  -> ProductCode vs MSI: '{00000000-0000-0000-0000-000000000000}' != '{FDF695BB-26FD-3E6F-B3A1-6071E297B937}'
  rejected: version drift in one file  -> PackageVersion across files: 2 != 1
  rejected: per-user scope  -> Scope vs MSI ALLUSERS: 'user' != 'machine'
ALL CHECKS PASSED
```

The negative control mutates the loaded manifests in memory and requires every mutation to be
rejected: by the schema (malformed hash, unknown `Scope`, missing `Installers`, unknown
`InstallerType`, identifier without a publisher) or by the MSI-agreement checks (a well-formed but
wrong hash, wrong `ProductCode`, version drift between files, per-user scope). It was also run once
against the real file: with the installer's hash edited on disk the script failed on both the
schema and the evidence check, and after the edit was reverted the file was byte-identical and the
script passed.

The first draft of the control included an unknown top-level field; the schema does not forbid
those, so it was not rejected. That mutation was replaced rather than the check weakened. The
schema therefore does not catch misspelled optional keys. Review them by eye.

## What this does not prove

JSON schema only checks shape. It says nothing about whether winget accepts the manifest, whether
the MSI installs, or what Add/Remove Programs shows. All of the following must still run on a
native Windows 10/11 machine (or Windows Sandbox):

1. `winget validate --manifest <version folder>`
2. `winget settings --enable LocalManifestFiles`, then
   `winget install --manifest <version folder>`: installs silently, SnapVault appears under
   *Installed apps*, and `winget list --id NajDev.SnapVault` reports 1.0.15 with an ARP entry
   that matches the `AppsAndFeaturesEntries` here
3. Launch the app once, then `winget uninstall --id NajDev.SnapVault`: it uninstalls cleanly
4. winget-pkgs' `Tools/SandboxTest.ps1` for the same install in a clean sandbox
5. Windows Defender / SmartScreen: the MSI is unsigned, and the winget-pkgs pipeline scans submitted
   installers

## Decisions for the owner

- **Identifier and publisher.** `NajDev.SnapVault` with `Publisher: NajDev` is a guess from the
  project's `com.najdev` package, the `najdev` Ko-fi handle and `naj-dev.com`. The identifier is
  permanent once merged. No `NajDev` publisher or `SnapVault` package exists in winget-pkgs today.
- **ARP publisher is `Unknown`.** The MSI's `Manufacturer` is literally `Unknown`, so the ARP entry
  says so. Fixing that needs a release (jpackage `vendor`) and a new manifest, not a manifest edit.
- **License.** `GPL-3.0-or-later`, taken from the v1.0.15 README ("v3.0 or later"). GitHub's license
  detector only reports `GPL-3.0`. Confirm which you intend.
- **Omitted on purpose:** `PrivacyUrl` (`PRIVACY.md` is not in the v1.0.15 tag), `PublisherUrl`
  (`naj-dev.com` is only a redirect and unverified), `Moniker`, and `Documentations`.
- **Staleness.** winget-pkgs takes one version per pull request and the hash is tied to this
  release. If a newer release ships before submission, regenerate the manifests for it. In
  particular the per-user installer setting in the working tree would change `Scope` and the
  MSI properties.
- **Submission** is a human step: fork `microsoft/winget-pkgs`, copy the version folder in, open
  the PR. The repository's PR checks and any contributor agreement are theirs, so check the
  current repository documentation. [Submission checks](https://learn.microsoft.com/en-us/windows/package-manager/package/repository)
