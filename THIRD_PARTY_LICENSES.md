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

## Offline map land geometry

`composeApp/src/commonMain/composeResources/files/world-land.json` is derived from Natural Earth's
1:110m land polygons (`ne_110m_land.geojson`), rounded to three decimal places for the global map.
Source: https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_110m_land.geojson
Retrieved November 6, 2026. Natural Earth data is public domain:
https://www.naturalearthdata.com/about/terms-of-use/ . Made with Natural Earth.
The map ships with the app and does not download map tiles or send memory coordinates anywhere.

## Offline desktop timezone lookup

Desktop includes Timeshape `net.iakovlev:timeshape:2026b.29` (MIT; license text in
`docs/licenses/timeshape-MIT.txt`), https://github.com/RomanIakovlev/timeshape . Its bundled
coordinate-to-timezone database comes from timezone-boundary-builder / OpenStreetMap and
is made available under the Open Data Commons Open Database License (ODbL) 1.0:
https://opendatacommons.org/licenses/odbl/1-0/ . Upstream database sources and downloadable
releases: https://github.com/evansiroky/timezone-boundary-builder/releases .
Database © OpenStreetMap contributors: https://www.openstreetmap.org/copyright .
No coordinate lookup is made over the network. The pinned Timeshape JAR is about 22 MiB;
its native decompression dependencies add to desktop distribution size. The boundary index
is initialized lazily during GPS metadata writing, not at app launch. Historical offsets
come from the bundled Java runtime's IANA rules; unknown zones or ambiguous matches produce
no inferred local offset. This feature is desktop-only; native mobile timezone writing
remains pending.
