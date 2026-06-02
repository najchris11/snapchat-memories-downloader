#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESOURCES_DIR="$ROOT_DIR/composeApp/src/desktopMain/resources/bin"
EXIFTOOL_VERSION="13.58"

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
FFMPEG_X64_URL="https://evermeet.cx/ffmpeg/getrelease/zip"
curl -fL "$FFMPEG_X64_URL" -o "$TMP_DIR/ffmpeg-x64.zip"
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
FFMPEG_ARM64_URL="https://www.osxexperts.net/ffmpeg81arm.zip"
curl -fL "$FFMPEG_ARM64_URL" -o "$TMP_DIR/ffmpeg-arm64.zip"
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