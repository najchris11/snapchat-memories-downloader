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
# Try SourceForge first (history URL 404s for older versions; direct exiftool.org
# URL sometimes 404s too — SourceForge is the most reliable archive mirror).
curl -fL "https://sourceforge.net/projects/exiftool/files/${EXIFTOOL_TARBALL}/download" -o "$TMP_DIR/$EXIFTOOL_TARBALL" || \
curl -fL "https://exiftool.org/${EXIFTOOL_TARBALL}" -o "$TMP_DIR/$EXIFTOOL_TARBALL"

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

# 2. FFmpeg is intentionally NOT bundled on Linux.
# A binary copied from apt is dynamically linked and fails on machines that don't
# already have ffmpeg's shared libraries — exactly the machines a bundled fallback
# would exist for. The app resolves ffmpeg from PATH at runtime instead, and the
# Settings screen tells users to install it via their package manager.

# 3. Copy packaged zip to resources folder
cp "$TMP_DIR/exiftool.zip" "$RESOURCES_DIR/linux-x64/exiftool.zip"

echo "Linux exiftool runtime successfully packaged under $RESOURCES_DIR"
echo "(ffmpeg is resolved from the system PATH on Linux — not bundled)"