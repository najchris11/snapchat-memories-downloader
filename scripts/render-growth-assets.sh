#!/usr/bin/env bash
# Render synthetic demo media through the real overlay and metadata pipeline.
set -euo pipefail
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
asset_dir=${1:-/tmp/snapvault-growth-artifacts}
exporter="$repo_dir/composeApp/src/desktopTest/kotlin/com/najdev/snapvault/ui/GrowthOverlayArtifactTest.kt"
[[ ! -e "$exporter" ]] || { echo "Refusing to overwrite $exporter" >&2; exit 1; }
mkdir -p "$asset_dir"
for artifact in before.jpg 2024-01-14_demo-main.jpg 2024-01-14_demo-overlay.png 2024-01-14_demo.jpg after-metadata.json; do
  [[ ! -e "$asset_dir/$artifact" ]] || { echo "Refusing to overwrite existing demo artifact: $artifact" >&2; exit 1; }
done
asset_dir=$(cd "$asset_dir" && pwd)
# The bundled macOS ExifTool distribution is portable Perl source, not a native executable.
tool_dir=$(mktemp -d "${TMPDIR:-/tmp}/snapvault-growth-tools.XXXXXX")
trap 'rm -f -- "$exporter"; rm -rf -- "$tool_dir"' EXIT
unzip -q "$repo_dir/composeApp/src/desktopMain/resources/bin/darwin-arm64/exiftool.zip" -d "$tool_dir"
export SNAPVAULT_GROWTH_EXIFTOOL="$tool_dir/exiftool-dist/exiftool"
export SNAPVAULT_GROWTH_OUTPUT="$asset_dir"
cp "$repo_dir/scripts/growth-assets/GrowthOverlayArtifactTest.kt.template" "$exporter"
(cd "$repo_dir" && ./gradlew :composeApp:desktopTest --tests '*GrowthOverlayArtifactTest')
cat > "$asset_dir/frames.txt" <<FRAME_LIST
file 'before.jpg'
duration 1.5
file '2024-01-14_demo.jpg'
duration 1.5
file '2024-01-14_demo.jpg'
FRAME_LIST
ffmpeg -v error -y -f concat -safe 0 -i "$asset_dir/frames.txt" -vf 'fps=4,scale=640:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer' -loop 0 "$asset_dir/overlay-demo.gif"
echo "Generated sample overlay demo and verified EXIF: $asset_dir"
