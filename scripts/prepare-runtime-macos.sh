#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESOURCES_DIR="$ROOT_DIR/composeApp/src/desktopMain/resources/bin"
EXIFTOOL_VERSION="13.58"

# Every download is pinned to a version and verified against the SHA-256 recorded in
# composeApp/src/desktopMain/resources/bin/manifest.json. A mismatch stops the script: a
# mirror serving different bytes, or a "latest" link that moved, must not reach an installer.
# ShippedToolManifestTest checks these hashes match the manifest.
EXIFTOOL_SHA256="c84fb6b613a480a638225d44979bf44cd2f91c92b79f4d2aa43773c89fa4199e"
FFMPEG_X64_URL="https://evermeet.cx/ffmpeg/ffmpeg-8.1.1.zip"
FFMPEG_X64_SHA256="4610988e2f54c243c50da73a09e4e2c36d9bb77546f9aa6c84cb328dcb1a98c1"
FFMPEG_ARM64_URL="https://www.osxexperts.net/ffmpeg81arm.zip"
FFMPEG_ARM64_SHA256="ebb82529562b71170807bbc6b0e7eb4f0b13af8cbb0e085bb9e8f6fe709598ad"

verify_sha256() {
  local file="$1" expected="$2" actual
  actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  if [[ "$actual" != "$expected" ]]; then
    echo "ERROR: $(basename "$file") has SHA-256 $actual, expected $expected" >&2
    exit 1
  fi
}

TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

# Create resources directories
mkdir -p "$RESOURCES_DIR/darwin-x64" "$RESOURCES_DIR/darwin-arm64"

# 1. Download ExifTool (Platform independent perl code, used for both arm64 and x64)
echo "Downloading ExifTool v${EXIFTOOL_VERSION}..."
EXIFTOOL_TARBALL="Image-ExifTool-${EXIFTOOL_VERSION}.tar.gz"
EXIFTOOL_URL="https://exiftool.org/history/${EXIFTOOL_TARBALL}"
curl -fL "$EXIFTOOL_URL" -o "$TMP_DIR/$EXIFTOOL_TARBALL" || \
curl -fL "https://exiftool.org/${EXIFTOOL_TARBALL}" -o "$TMP_DIR/$EXIFTOOL_TARBALL" || \
curl -fL "https://sourceforge.net/projects/exiftool/files/${EXIFTOOL_TARBALL}/download" -o "$TMP_DIR/$EXIFTOOL_TARBALL"

verify_sha256 "$TMP_DIR/$EXIFTOOL_TARBALL" "$EXIFTOOL_SHA256"
tar -xzf "$TMP_DIR/$EXIFTOOL_TARBALL" -C "$TMP_DIR"
EXIFTOOL_SRC_DIR=$(find "$TMP_DIR" -maxdepth 1 -type d -name "Image-ExifTool-*" | head -n 1)

# Create a zip structure for ExifTool
EXIFTOOL_ZIP_DIR="$TMP_DIR/exiftool-zip-structure"
mkdir -p "$EXIFTOOL_ZIP_DIR/exiftool-dist"
cp -R "$EXIFTOOL_SRC_DIR"/* "$EXIFTOOL_ZIP_DIR/exiftool-dist"

cat > "$EXIFTOOL_ZIP_DIR/exiftool" <<'EOF'
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec /usr/bin/env perl "$SCRIPT_DIR/exiftool-dist/exiftool" "$@"
EOF
chmod +x "$EXIFTOOL_ZIP_DIR/exiftool"

# Package exiftool.zip
(
  cd "$EXIFTOOL_ZIP_DIR"
  zip -q -r "$TMP_DIR/exiftool.zip" exiftool exiftool-dist
)

# 2. Download FFmpeg macOS x64
echo "Downloading FFmpeg macOS x64..."
curl -fL "$FFMPEG_X64_URL" -o "$TMP_DIR/ffmpeg-x64.zip"
verify_sha256 "$TMP_DIR/ffmpeg-x64.zip" "$FFMPEG_X64_SHA256"
mkdir -p "$TMP_DIR/ffmpeg-x64-extracted"
unzip -q "$TMP_DIR/ffmpeg-x64.zip" -d "$TMP_DIR/ffmpeg-x64-extracted"

FFMPEG_BIN_X64=$(find "$TMP_DIR/ffmpeg-x64-extracted" -type f -name "ffmpeg" | head -n 1)
if [[ -z "$FFMPEG_BIN_X64" ]]; then
  echo "ERROR: ffmpeg binary not found in x64 archive" >&2
  exit 1
fi
mkdir -p "$TMP_DIR/ffmpeg-x64-extracted-bin"
cp "$FFMPEG_BIN_X64" "$TMP_DIR/ffmpeg-x64-extracted-bin/ffmpeg"
chmod +x "$TMP_DIR/ffmpeg-x64-extracted-bin/ffmpeg"

(
  cd "$TMP_DIR/ffmpeg-x64-extracted-bin"
  zip -q "$TMP_DIR/ffmpeg-x64-packaged.zip" ffmpeg
)

# 3. Download FFmpeg macOS arm64
echo "Downloading FFmpeg macOS arm64..."
curl -fL "$FFMPEG_ARM64_URL" -o "$TMP_DIR/ffmpeg-arm64.zip"
verify_sha256 "$TMP_DIR/ffmpeg-arm64.zip" "$FFMPEG_ARM64_SHA256"
mkdir -p "$TMP_DIR/ffmpeg-arm64-extracted"
unzip -q "$TMP_DIR/ffmpeg-arm64.zip" -d "$TMP_DIR/ffmpeg-arm64-extracted"

FFMPEG_BIN_ARM64=$(find "$TMP_DIR/ffmpeg-arm64-extracted" -type f -name "ffmpeg" | head -n 1)
if [[ -z "$FFMPEG_BIN_ARM64" ]]; then
  echo "ERROR: ffmpeg binary not found in arm64 archive" >&2
  exit 1
fi
mkdir -p "$TMP_DIR/ffmpeg-arm64-extracted-bin"
cp "$FFMPEG_BIN_ARM64" "$TMP_DIR/ffmpeg-arm64-extracted-bin/ffmpeg"
chmod +x "$TMP_DIR/ffmpeg-arm64-extracted-bin/ffmpeg"

(
  cd "$TMP_DIR/ffmpeg-arm64-extracted-bin"
  zip -q "$TMP_DIR/ffmpeg-arm64-packaged.zip" ffmpeg
)

# 4. Copy packaged zips to resources folders
cp "$TMP_DIR/exiftool.zip" "$RESOURCES_DIR/darwin-x64/exiftool.zip"
cp "$TMP_DIR/exiftool.zip" "$RESOURCES_DIR/darwin-arm64/exiftool.zip"
cp "$TMP_DIR/ffmpeg-x64-packaged.zip" "$RESOURCES_DIR/darwin-x64/ffmpeg.zip"
cp "$TMP_DIR/ffmpeg-arm64-packaged.zip" "$RESOURCES_DIR/darwin-arm64/ffmpeg.zip"

echo "macOS runtimes successfully packaged under $RESOURCES_DIR"
echo "Update archiveSha256 in bin/manifest.json for the new archives:"
shasum -a 256 "$RESOURCES_DIR"/darwin-*/*.zip