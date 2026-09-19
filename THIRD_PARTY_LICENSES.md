# Third-Party Software

SnapVault itself is GPL-licensed (see `LICENSE`). The desktop installers additionally
bundle the following third-party tools, which remain under their own licenses.

## ExifTool

- **What:** metadata read/write engine, invoked as an external process.
- **Author:** Phil Harvey — https://exiftool.org/
- **License:** dual-licensed under the Perl Artistic License and the GNU General Public
  License (same terms as Perl itself). License text: https://dev.perl.org/licenses/
- **Bundled on:** macOS (arm64, x64), Windows (x64), Linux (x64 — as the unmodified Perl
  distribution plus a wrapper script; requires the system `perl`).
- **Source:** the bundled copy *is* the source (ExifTool is a Perl program). Obtained from
  https://exiftool.org/ — see `scripts/prepare-runtime-*.{sh,ps1}` for the exact packaging.

## FFmpeg

- **What:** video/image compositing engine, invoked as an external process.
- **Project:** https://ffmpeg.org/
- **License:** GNU General Public License (the bundled builds are GPL-enabled static
  builds): GPL-3.0-or-later for the macOS x64 and Windows builds, which enable version 3,
  and GPL-2.0-or-later for the macOS arm64 build. License texts:
  https://www.gnu.org/licenses/gpl-3.0.html and https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
  — see also https://ffmpeg.org/legal.html
- **Bundled on:**
  - macOS x64 — static build from https://evermeet.cx/ffmpeg/
  - macOS arm64 — static build from https://www.osxexperts.net/
  - Windows x64 — "release essentials" build from https://www.gyan.dev/ffmpeg/builds/
  - **Linux — not bundled.** SnapVault uses the ffmpeg found on your PATH; install it via
    your distribution's package manager (e.g. `sudo apt install ffmpeg`).
- **Corresponding source:** FFmpeg sources for every release are available at
  https://ffmpeg.org/download.html#get-sources ; the build pages above document each
  build's configuration and version. On request, the SnapVault maintainers will provide a
  copy of the corresponding source for any FFmpeg binary distributed with a SnapVault
  release (open an issue on this repository).

## Exact builds

Every bundled archive — tool, version, pinned download URL, SHA-256 of the download, of the
archive and of the executable, license, and source — is recorded in
`composeApp/src/desktopMain/resources/bin/manifest.json`, which also ships inside the app as
`bin/manifest.json`. Each executable was checked byte-for-byte against its upstream download.

## Runtime

The installers embed an OpenJDK-based Java runtime (JetBrains Runtime / OpenJDK,
GPLv2 with Classpath Exception — https://openjdk.org/legal/gplv2+ce.html) produced by
`jpackage` as part of the Compose Desktop packaging process.
