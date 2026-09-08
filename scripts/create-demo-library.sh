#!/usr/bin/env bash
set -euo pipefail

# Creates a safe, synthetic Library folder for screenshots and product demos. It intentionally
# refuses an existing non-empty destination so it can never mix with or overwrite real memories.
demo_dir="${1:-demo-library}"
generator_dir="$(mktemp -d)"
trap 'rm -rf "$generator_dir"' EXIT

if [[ -e "$demo_dir" && ! -d "$demo_dir" ]]; then
    echo "Destination exists and is not a directory: $demo_dir" >&2
    exit 1
fi
if [[ -d "$demo_dir" && -n "$(find "$demo_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    echo "Refusing to write into a non-empty destination: $demo_dir" >&2
    exit 1
fi

mkdir -p "$demo_dir"
javac -d "$generator_dir" "$(dirname "$0")/DemoLibraryGenerator.java"
java -Djava.awt.headless=true -cp "$generator_dir" DemoLibraryGenerator "$demo_dir"
echo "Demo library created at: $(cd "$demo_dir" && pwd)"
