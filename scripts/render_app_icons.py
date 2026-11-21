"""Regenerate every platform's app icon from branding/app-icon.svg.

Requires rsvg-convert (librsvg) and ImageMagick 7 (`magick`). Run from anywhere:

    python3 scripts/render_app_icons.py

Outputs (all committed, so a build never needs these tools):
  - iOS:     iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png (opaque; iOS
             applies its own mask, and App Store Connect rejects an alpha channel)
  - Android: an adaptive icon (gradient background + foreground scaled into the 66dp safe
             zone, so circle and squircle masks never clip the download badge), plus legacy
             square and round bitmaps for API 24-25
  - Desktop: AppIcon.png/.ico/.icns and the in-app ic_launcher.png, drawn as a rounded tile on
             the macOS 824/1024 grid with a transparent margin, like other desktop app icons
"""

from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "branding" / "app-icon.svg"
ANDROID_RES = ROOT / "composeApp" / "src" / "androidMain" / "res"
IOS_ICONSET = ROOT / "iosApp" / "iosApp" / "Assets.xcassets" / "AppIcon.appiconset"
DESKTOP = ROOT / "composeApp" / "src" / "desktopMain" / "resources"
IN_APP = ROOT / "composeApp" / "src" / "commonMain" / "composeResources" / "drawable" / "ic_launcher.png"

LEGACY_DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
# The card stack is centred in the source, so the foreground is only scaled, never shifted:
# shifting it to make room for the badge put the cards visibly off-centre under every mask.
# At this scale the badge, the farthest point from the centre, still clears the 72dp visible
# circle of a round launcher mask.
FOREGROUND_SCALE = 0.66
# Legacy bitmaps are drawn already masked, so there is no safe zone to stay inside.
LEGACY_SCALE = 0.88
DESKTOP_TILE = 824  # macOS Big Sur icon grid: an 824px body on a 1024px canvas
DESKTOP_RADIUS = 185
BACKGROUND_START = "#A078FF"
BACKGROUND_END = "#5B2FC4"


def parts(svg: str):
    defs = re.search(r"<defs>(.*?)</defs>", svg, re.S).group(1)
    background = re.search(r'<rect id="background"[^>]*/>', svg).group(0)
    artwork = svg[svg.index('<g id="artwork">'):svg.rindex("</svg>")]
    return defs, background, artwork


def document(defs: str, body: str) -> str:
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024">'
        f"<defs>{defs}</defs>{body}</svg>"
    )


def render(svg: str, size: int, out: Path, work: Path, opaque: bool = False) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    src = work / f"{out.stem}-{size}.svg"
    src.write_text(svg)
    subprocess.run(["rsvg-convert", "-w", str(size), "-h", str(size), str(src), "-o", str(out)], check=True)
    if opaque:
        subprocess.run(["magick", str(out), "-background", BACKGROUND_END, "-alpha", "remove", "-alpha", "off",
                        "-define", "png:color-type=2", str(out)], check=True)


def foreground_body(artwork: str, scale: float = FOREGROUND_SCALE) -> str:
    return f'<g transform="translate(512 512) scale({scale}) translate(-512 -512)">{artwork}</g>'


def write_icns(entries, out: Path) -> None:
    chunks = b"".join(kind + struct.pack(">I", 8 + len(data)) + data for kind, data in entries)
    out.write_bytes(b"icns" + struct.pack(">I", 8 + len(chunks)) + chunks)


def main() -> None:
    defs, background, artwork = parts(SOURCE.read_text())
    full = document(defs, background + artwork)
    tile = document(defs, (
        f'<clipPath id="desktop-tile"><rect x="{(1024 - DESKTOP_TILE) / 2}" y="{(1024 - DESKTOP_TILE) / 2}" '
        f'width="{DESKTOP_TILE}" height="{DESKTOP_TILE}" rx="{DESKTOP_RADIUS}"/></clipPath>'
        f'<g clip-path="url(#desktop-tile)"><g transform="translate(512 512) scale({DESKTOP_TILE / 1024}) '
        f'translate(-512 -512)">{background}{artwork}</g></g>'
    ))
    foreground = document(defs, foreground_body(artwork))
    legacy_square = document(defs, (
        '<clipPath id="legacy"><rect x="40" y="40" width="944" height="944" rx="190"/></clipPath>'
        f'<g clip-path="url(#legacy)">{background}{foreground_body(artwork, LEGACY_SCALE)}</g>'
    ))
    legacy_round = document(defs, (
        '<clipPath id="legacy"><circle cx="512" cy="512" r="472"/></clipPath>'
        f'<g clip-path="url(#legacy)">{background}{foreground_body(artwork, LEGACY_SCALE)}</g>'
    ))

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)

        # iOS
        render(full, 1024, IOS_ICONSET / "AppIcon-1024.png", work, opaque=True)

        # Android
        for density, scale in LEGACY_DENSITIES.items():
            folder = ANDROID_RES / f"mipmap-{density}"
            render(foreground, round(108 * scale), folder / "ic_launcher_foreground.png", work)
            render(legacy_square, round(48 * scale), folder / "ic_launcher.png", work)
            render(legacy_round, round(48 * scale), folder / "ic_launcher_round.png", work)

        # Desktop
        sizes = {}
        for size in (16, 24, 32, 48, 64, 128, 256, 512, 1024):
            sizes[size] = work / f"desktop-{size}.png"
            render(tile, size, sizes[size], work)
        shutil.copyfile(sizes[512], DESKTOP / "AppIcon.png")
        shutil.copyfile(sizes[512], IN_APP)
        subprocess.run(["magick", *(str(sizes[s]) for s in (16, 24, 32, 48, 64, 128, 256)),
                        str(DESKTOP / "AppIcon.ico")], check=True)
        icns_types = [(b"icp4", 16), (b"icp5", 32), (b"icp6", 64), (b"ic07", 128), (b"ic08", 256),
                      (b"ic09", 512), (b"ic10", 1024), (b"ic11", 32), (b"ic12", 64), (b"ic13", 256), (b"ic14", 512)]
        write_icns([(kind, sizes[size].read_bytes()) for kind, size in icns_types], DESKTOP / "AppIcon.icns")

    print("App icons regenerated from", SOURCE.relative_to(ROOT))


if __name__ == "__main__":
    main()
