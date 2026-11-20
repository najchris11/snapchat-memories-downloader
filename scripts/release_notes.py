#!/usr/bin/env python3
"""Generates user-facing GitHub Release notes for .github/workflows/release.yml.

`gh release create` without `--notes-file` left v1.0.12, v1.0.13, and v1.0.15 with
`body: null`, leaving non-technical visitors facing six bare asset filenames with no
hint about Apple Silicon vs Intel or macOS Gatekeeper / Windows SmartScreen prompts.
"""

import argparse
import re
import subprocess
import sys

_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
_RELEASE_CHORE = re.compile(r"^chore:\s*release\s+v\d+\.\d+\.\d+\b", re.IGNORECASE)


def _parse_tag(tag):
    match = _TAG.match(tag.strip())
    if not match:
        raise ValueError(f"'{tag}' is not a vMAJOR.MINOR.PATCH release tag")
    return tuple(int(part) for part in match.groups())


def previous_release_tag(current_tag, tags):
    current = _parse_tag(current_tag)
    older = []
    for raw in tags:
        match = _TAG.match(raw.strip())
        if match:
            parsed = tuple(int(part) for part in match.groups())
            if parsed < current:
                older.append((parsed, raw.strip()))
    if not older:
        return None
    return max(older, key=lambda item: item[0])[1]


def render_release_notes(tag, previous_tag, commit_subjects, repo):
    _parse_tag(tag)
    version = tag[1:]
    visible_commits = [
        subject.strip()
        for subject in commit_subjects
        if subject.strip() and not _RELEASE_CHORE.match(subject.strip())
    ]

    lines = [
        "## Which installer should I download?",
        "",
        "| Operating System | File | Notes |",
        "|---|---|---|",
        f"| **macOS (Apple Silicon — M1 / M2 / M3 / M4)** | `SnapVault-{version}-macos-arm64.dmg` | Most Macs from late 2020 onward (ExifTool + FFmpeg bundled) |",
        f"| **macOS (Intel)** | `SnapVault-{version}-macos-x64.dmg` | Pre-2021 Intel Macs (ExifTool + FFmpeg bundled) |",
        f"| **Windows 10 / 11 (64-bit)** | `SnapVault-{version}-windows-x64.msi` | Per-user installer; ExifTool + FFmpeg bundled |",
        f"| **Linux (Ubuntu / Debian x86_64)** | `SnapVault-{version}-linux-x64.deb` | ExifTool bundled (requires `perl`); install `ffmpeg` via `apt` for video overlays |",
        "",
        "> **Step-by-step guide & 2-minute walkthrough:** https://snapvault.naj-dev.com/tutorial",
        "",
        "### First-launch notes (unsigned open-source builds)",
        "- **macOS (Sequoia 15+ / Sonoma):** Drag **SnapVault** to **Applications** and try to open it once. When macOS blocks it, open **System Settings → Privacy & Security**, scroll down, and click **Open Anyway**.",
        "- **Windows:** If SmartScreen displays *Windows protected your PC*, click **More info → Run anyway**.",
        "- **Linux:** Install runtime prerequisites for metadata & video overlay compositing: `sudo apt install ffmpeg perl`.",
        "",
    ]

    if visible_commits:
        lines.append("## What's Changed")
        lines.append("")
        for subject in visible_commits:
            lines.append(f"- {subject}")
        lines.append("")

    if previous_tag:
        lines.append(
            f"**Full Changelog:** https://github.com/{repo}/compare/{previous_tag}...{tag}"
        )
        lines.append("")

    lines.extend(
        [
            "## Verification",
            "- Compare your downloaded installer against `SHA256SUMS.txt` (`shasum -a 256` on macOS/Linux or `Get-FileHash -Algorithm SHA256` in PowerShell).",
            "- `bundled-tools-manifest.json` records exact upstream URLs, SHA-256 digests, and licenses for every bundled ExifTool and FFmpeg archive.",
        ]
    )
    return "\n".join(lines) + "\n"


def _git_lines(*args):
    out = subprocess.check_output(["git", *args], text=True)
    return [line for line in out.splitlines() if line.strip()]


def main(argv):
    parser = argparse.ArgumentParser(description="Generate release notes markdown.")
    parser.add_argument("--tag", required=True, help="Release tag (e.g. v1.0.16)")
    parser.add_argument(
        "--repo",
        default="najchris11/snapchat-memories-downloader",
        help="GitHub owner/repo slug",
    )
    parser.add_argument("--output", required=True, help="Destination markdown path")
    args = parser.parse_args(argv)

    try:
        all_tags = _git_lines("tag", "--list", "v*")
        prev = previous_release_tag(args.tag, all_tags)
        rev_range = f"{prev}..{args.tag}" if prev else args.tag
        subjects = _git_lines("log", "--no-merges", "--format=%s", rev_range)
        notes = render_release_notes(args.tag, prev, subjects, args.repo)
    except (ValueError, subprocess.CalledProcessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    with open(args.output, "w", encoding="utf-8") as handle:
        handle.write(notes)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
