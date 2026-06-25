#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESOURCES_DIR="$ROOT_DIR/composeApp/src/desktopMain/resources/bin"
EXIFTOOL_VERSION="13.58"

TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

# Create resources directories
mkdir -p "$RESOURCES_DIR/linux-x64"

# 1. Download ExifTool (Platform independent perl code)
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

# 2. Download FFmpeg Linux amd64 static build
echo "Downloading FFmpeg Linux x64..."
FFMPEG_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"
curl -L "$FFMPEG_URL" -o "$TMP_DIR/ffmpeg.tar.xz"
tar -xJf "$TMP_DIR/ffmpeg.tar.xz" -C "$TMP_DIR"

FFMPEG_BIN=$(find "$TMP_DIR" -maxdepth 4 -type f -name "ffmpeg" | head -n 1)
if [[ -z "$FFMPEG_BIN" ]]; then
  echo "ERROR: ffmpeg binary not found in archive" >&2
  exit 1
fi

mkdir -p "$TMP_DIR/ffmpeg-linux-extracted"
cp "$FFMPEG_BIN" "$TMP_DIR/ffmpeg-linux-extracted/ffmpeg"
chmod +x "$TMP_DIR/ffmpeg-linux-extracted/ffmpeg"

(
  cd "$TMP_DIR/ffmpeg-linux-extracted"
  zip -q "$TMP_DIR/ffmpeg-packaged.zip" ffmpeg
)

# 3. Copy packaged zips to resources folders
cp "$TMP_DIR/exiftool.zip" "$RESOURCES_DIR/linux-x64/exiftool.zip"
cp "$TMP_DIR/ffmpeg-packaged.zip" "$RESOURCES_DIR/linux-x64/ffmpeg.zip"

echo "Linux runtimes successfully packaged under $RESOURCES_DIR"