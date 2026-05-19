#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/runtime"
PYTHON_DIR="$RUNTIME_DIR/python"
TOOLS_DIR="$RUNTIME_DIR/tools/linux"

PY_VERSION="3.11.15"
PY_RELEASE="20260510"
EXIFTOOL_VERSION="13.58"
ARCH="$(uname -m)"

if [[ "$ARCH" == "aarch64" || "$ARCH" == "arm64" ]]; then
  PY_PLATFORM="aarch64-unknown-linux-gnu"
else
  PY_PLATFORM="x86_64-unknown-linux-gnu"
fi

PY_TARBALL="cpython-${PY_VERSION}+${PY_RELEASE}-${PY_PLATFORM}-install_only.tar.gz"
PY_URL="https://github.com/astral-sh/python-build-standalone/releases/download/${PY_RELEASE}/${PY_TARBALL}"
EXIFTOOL_TARBALL="Image-ExifTool-${EXIFTOOL_VERSION}.tar.gz"
EXIFTOOL_URL="https://sourceforge.net/projects/exiftool/files/${EXIFTOOL_TARBALL}/download"

mkdir -p "$PYTHON_DIR" "$TOOLS_DIR"

TMP_DIR="$(mktemp -d)"
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

sha256_check() {
  local file="$1"
  local expected="$2"
  if [[ -z "$expected" ]]; then
    echo "WARN: No SHA256 provided for $(basename "$file"); skipping checksum verification."
    return 0
  fi

  local actual
  if command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$file" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    actual=$(shasum -a 256 "$file" | awk '{print $1}')
  else
    echo "ERROR: No SHA256 tool found (sha256sum/shasum)." >&2
    return 1
  fi

  if [[ "$actual" != "$expected" ]]; then
    echo "ERROR: SHA256 mismatch for $(basename "$file")." >&2
    echo "Expected: $expected" >&2
    echo "Actual:   $actual" >&2
    return 1
  fi
}

echo "Downloading Python runtime: $PY_URL"
curl -L "$PY_URL" -o "$TMP_DIR/$PY_TARBALL"
sha256_check "$TMP_DIR/$PY_TARBALL" "${PYTHON_SHA256:-}"

rm -rf "$PYTHON_DIR"/*
tar -xzf "$TMP_DIR/$PY_TARBALL" -C "$PYTHON_DIR" --strip-components=1

PYTHON_BIN="$PYTHON_DIR/bin/python3"
"$PYTHON_BIN" -m ensurepip --upgrade
"$PYTHON_BIN" -m pip install -q --upgrade pip
"$PYTHON_BIN" -m pip install -q -r "$ROOT_DIR/requirements.txt"

echo "Downloading exiftool..."
curl -L "$EXIFTOOL_URL" -o "$TMP_DIR/$EXIFTOOL_TARBALL"
sha256_check "$TMP_DIR/$EXIFTOOL_TARBALL" "${EXIFTOOL_SHA256:-}"
tar -xzf "$TMP_DIR/$EXIFTOOL_TARBALL" -C "$TMP_DIR"

EXIFTOOL_SRC_DIR=$(find "$TMP_DIR" -maxdepth 1 -type d -name "Image-ExifTool-*" | head -n 1)
if [[ -z "$EXIFTOOL_SRC_DIR" ]]; then
  echo "ERROR: exiftool source directory not found in archive" >&2
  exit 1
fi

EXIFTOOL_DST_DIR="$TOOLS_DIR/exiftool-dist"
rm -rf "$EXIFTOOL_DST_DIR"
mkdir -p "$EXIFTOOL_DST_DIR"
cp -R "$EXIFTOOL_SRC_DIR"/* "$EXIFTOOL_DST_DIR"

cat > "$TOOLS_DIR/exiftool" <<'EOF'
#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec /usr/bin/env perl "$SCRIPT_DIR/exiftool-dist/exiftool" "$@"
EOF
chmod +x "$TOOLS_DIR/exiftool"

echo "Downloading ffmpeg..."
if [[ "$ARCH" == "aarch64" || "$ARCH" == "arm64" ]]; then
  FFMPEG_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-arm64-static.tar.xz"
else
  FFMPEG_URL="https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz"
fi
curl -L "$FFMPEG_URL" -o "$TMP_DIR/ffmpeg.tar.xz"
sha256_check "$TMP_DIR/ffmpeg.tar.xz" "${FFMPEG_SHA256:-}"
tar -xJf "$TMP_DIR/ffmpeg.tar.xz" -C "$TMP_DIR"

FFMPEG_BIN=$(find "$TMP_DIR" -maxdepth 4 -type f -name "ffmpeg" | head -n 1)
if [[ -z "$FFMPEG_BIN" ]]; then
  echo "ERROR: ffmpeg binary not found in archive" >&2
  exit 1
fi

cp "$FFMPEG_BIN" "$TOOLS_DIR/ffmpeg"
chmod +x "$TOOLS_DIR/ffmpeg"

echo "Runtime prepared at $RUNTIME_DIR"