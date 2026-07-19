# Code Signing & Notarization Roadmap

SnapVault releases are currently **unsigned**. This document covers what users see today,
and the exact steps to sign macOS and Windows builds when certificates are acquired.

## Current state (unsigned)

| Platform | What users see | Workaround |
|---|---|---|
| macOS | Gatekeeper: "SnapVault can't be opened because it is from an unidentified developer" | Right-click the app → **Open** → **Open**, or `xattr -d com.apple.quarantine /Applications/SnapVault.app` |
| Windows | SmartScreen: "Windows protected your PC" | **More info** → **Run anyway** |
| Linux | No warning — `.deb` packages aren't signature-checked by default | — |

---

## macOS: Developer ID + notarization

### 1. Get the certificate

1. Enroll in the [Apple Developer Program](https://developer.apple.com/programs/) ($99/year).
2. In [Certificates, Identifiers & Profiles](https://developer.apple.com/account/resources/certificates/list),
   create a **Developer ID Application** certificate (this is the one for apps distributed
   *outside* the Mac App Store — not "Apple Distribution").
3. Download it and add it to the login keychain of the build machine. Verify with:
   ```sh
   security find-identity -v -p codesigning
   # → "Developer ID Application: Your Name (TEAMID)"
   ```
4. Create an app-specific password for notarization at [appleid.apple.com](https://appleid.apple.com)
   → Sign-In and Security → App-Specific Passwords.

### 2. Configure Gradle

Compose Desktop has first-class support — add to the `macOS { }` block in
`composeApp/build.gradle.kts`:

```kotlin
macOS {
    iconFile.set(project.file("src/desktopMain/resources/AppIcon.icns"))
    signing {
        sign.set(true)
        identity.set("Your Name")           // as it appears in the certificate
        // keychain.set("/path/to/custom.keychain") // only if not in login keychain
    }
    notarization {
        appleID.set(providers.gradleProperty("compose.notarization.appleID"))
        password.set(providers.gradleProperty("compose.notarization.password")) // app-specific password
        teamID.set(providers.gradleProperty("compose.notarization.teamID"))
    }
}
```

Keep the three values out of the repo — put them in `~/.gradle/gradle.properties` locally,
or CI secrets (below).

With this in place, `packageReleaseDistributionForCurrentOS` signs automatically, and
`notarizeDmg` submits to Apple (usually completes in a few minutes) and staples the ticket.

**Important:** the bundled `ffmpeg`/`exiftool` binaries extracted at runtime to
`~/.snapvault/bin` are *not* part of the signed app bundle, so they're quarantined on
first extraction from a notarized app. Test a full pipeline run on a clean machine after
signing; if Gatekeeper blocks the extracted binaries, they need `codesign --force --sign`
applied during `prepare-runtime-macos.sh` before being zipped, or an `xattr -d` after
extraction in `BinaryExtractor`.

### 3. CI (release.yml)

Add repository secrets, then in the macOS job before packaging:

```yaml
- name: Import signing certificate
  if: runner.os == 'macOS'
  env:
    CERT_P12_BASE64: ${{ secrets.MACOS_CERT_P12_BASE64 }}   # base64 of exported .p12
    CERT_PASSWORD: ${{ secrets.MACOS_CERT_PASSWORD }}
  run: |
    echo "$CERT_P12_BASE64" | base64 --decode > cert.p12
    security create-keychain -p ci-keychain build.keychain
    security default-keychain -s build.keychain
    security unlock-keychain -p ci-keychain build.keychain
    security import cert.p12 -k build.keychain -P "$CERT_PASSWORD" -T /usr/bin/codesign
    security set-key-partition-list -S apple-tool:,apple: -s -k ci-keychain build.keychain

- name: Package (signed + notarized)
  if: runner.os == 'macOS'
  run: |
    ./gradlew packageReleaseDistributionForCurrentOS notarizeDmg \
      -Pcompose.javaHome="$JAVA_HOME" \
      -Pcompose.notarization.appleID="${{ secrets.APPLE_ID }}" \
      -Pcompose.notarization.password="${{ secrets.APPLE_APP_PASSWORD }}" \
      -Pcompose.notarization.teamID="${{ secrets.APPLE_TEAM_ID }}"
```

---

## Windows: Authenticode

### 1. Choose a certificate route

| Route | Cost (approx.) | SmartScreen reputation |
|---|---|---|
| **Azure Trusted Signing** (recommended) | ~$10/month | Good — Microsoft-managed identity validation |
| OV code-signing cert (Sectigo, SSL.com, …) | ~$100–250/year | Builds slowly as downloads accumulate |
| EV code-signing cert | ~$250–400/year | Immediate reputation, but requires hardware token (awkward in CI) |

Since June 2023 all traditional certs ship on hardware tokens or cloud HSMs (no plain
`.pfx` files), which makes **Azure Trusted Signing** the most CI-friendly option: it's
cheap, key management is Microsoft's problem, and there's an official GitHub Action.

### 2. Azure Trusted Signing setup

1. Create an Azure account and a **Trusted Signing** resource (East US or West Europe).
2. Complete identity validation (individual or organization).
3. Create a certificate profile (public trust).
4. Create a service principal / app registration with the *Trusted Signing Certificate
   Profile Signer* role, and note tenant ID, client ID, client secret.

### 3. CI (release.yml)

Compose Desktop's MSI task doesn't sign, so sign the artifact after packaging:

```yaml
- name: Sign MSI
  if: runner.os == 'Windows'
  uses: azure/trusted-signing-action@v4
  with:
    azure-tenant-id: ${{ secrets.AZURE_TENANT_ID }}
    azure-client-id: ${{ secrets.AZURE_CLIENT_ID }}
    azure-client-secret: ${{ secrets.AZURE_CLIENT_SECRET }}
    endpoint: https://eus.codesigning.azure.net/
    trusted-signing-account-name: <account-name>
    certificate-profile-name: <profile-name>
    files-folder: composeApp/build/compose/binaries/main-release/msi
    files-folder-filter: msi
```

(For a traditional cert instead, the equivalent is
`signtool sign /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 <file>.msi`.)

---

## Linux

`.deb` packages install without signature checks, so nothing is required for the current
distribution model. If SnapVault ever ships via an apt repository, sign the repository
metadata with a GPG key at that point (users add the key alongside the source).

---

## Checklist when the certificates arrive

- [ ] macOS: Developer ID Application cert in CI keychain (secrets: `MACOS_CERT_P12_BASE64`, `MACOS_CERT_PASSWORD`)
- [ ] macOS: notarization secrets (`APPLE_ID`, `APPLE_APP_PASSWORD`, `APPLE_TEAM_ID`)
- [ ] macOS: `signing`/`notarization` blocks added to `composeApp/build.gradle.kts`
- [ ] macOS: verify the runtime-extracted ffmpeg/exiftool still run on a clean machine
- [ ] Windows: Trusted Signing resource + identity validation complete
- [ ] Windows: signing step added to `release.yml` after packaging
- [ ] Both: cut a test release and install on clean VMs (no dev tools) to confirm no warnings
- [ ] README: remove the unsigned-builds workaround note from Quickstart
