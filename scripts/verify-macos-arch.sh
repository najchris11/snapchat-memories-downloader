#!/bin/bash
# Fails unless a packaged SnapVault targets exactly the expected CPU architecture.
#
#   scripts/verify-macos-arch.sh <SnapVault.dmg | SnapVault.app> <arm64 | x86_64>
#
# jpackage bundles the JDK of the machine that built the app, so a DMG is single-architecture
# whatever it is named, and bundling FFmpeg for both architectures does not change that (D17).
# The release workflow runs this on every DMG before anything is published: an Apple Silicon
# build uploaded as the Intel download would install and then fail to start.
set -euo pipefail

TARGET="${1:?usage: verify-macos-arch.sh <dmg|app> <arm64|x86_64>}"
EXPECTED="${2:?usage: verify-macos-arch.sh <dmg|app> <arm64|x86_64>}"

MOUNT=""
cleanup() { [[ -n "$MOUNT" ]] && hdiutil detach -quiet "$MOUNT" || true; }
trap cleanup EXIT

if [[ "$TARGET" == *.dmg ]]; then
  MOUNT="$(mktemp -d)"
  hdiutil attach -quiet -nobrowse -readonly -mountpoint "$MOUNT" "$TARGET"
  APP="$(find "$MOUNT" -maxdepth 1 -name '*.app' | head -n 1)"
else
  APP="$TARGET"
fi
[[ -d "$APP" ]] || { echo "ERROR: no .app found in $TARGET" >&2; exit 1; }

# The launcher and the JVM it loads; both must match, since either one on the wrong
# architecture stops the app from starting.
status=0
for binary in "$APP/Contents/MacOS/"* "$APP/Contents/runtime/Contents/Home/lib/server/libjvm.dylib"; do
  [[ -f "$binary" ]] || { echo "ERROR: missing $binary" >&2; status=1; continue; }
  archs="$(lipo -archs "$binary")"
  if [[ " $archs " != *" $EXPECTED "* ]]; then
    echo "ERROR: ${binary#"$APP"/} is $archs, expected $EXPECTED" >&2
    status=1
  else
    echo "ok: ${binary#"$APP"/} is $archs"
  fi
done
exit $status
