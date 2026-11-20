"""Every platform must ship the SnapVault app icon, generated from branding/app-icon.svg.

Until 2026-11 desktop used a purple Snapchat-ghost icon, Android declared the system
placeholder (@android:mipmap/sym_def_app_icon) and iOS had no asset catalog at all, so the
home screen showed a blank grid. These checks pin the wiring, not the artwork: that each
platform points at a real icon of the size its store and launcher require.
"""

from pathlib import Path
import hashlib
import json
import re
import struct
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "composeApp" / "src"
ANDROID_RES = APP / "androidMain" / "res"
IOS_ICONSET = ROOT / "iosApp" / "iosApp" / "Assets.xcassets" / "AppIcon.appiconset"
PBXPROJ = ROOT / "iosApp" / "iosApp.xcodeproj" / "project.pbxproj"
DESKTOP = APP / "desktopMain" / "resources"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
LEGACY_DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# sha256 of the old ghost AppIcon.png, so the ghost cannot quietly come back.
GHOST_SHA256 = "c7b5b09b26afc9fbf782c3fbc211a8bce199e82bc6f7b6683c99b2dc4c112dd4"


def png_header(path: Path):
    data = path.read_bytes()[:33]
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise AssertionError(f"{path} is not a PNG")
    width, height, _depth, colour_type = struct.unpack(">IIBB", data[16:26])
    return width, height, colour_type


class AppIconTest(unittest.TestCase):
    def test_the_source_artwork_is_checked_in(self):
        self.assertTrue((ROOT / "branding" / "app-icon.svg").is_file())
        self.assertTrue((ROOT / "scripts" / "render_app_icons.py").is_file())

    def test_android_declares_an_adaptive_launcher_icon(self):
        manifest = ET.parse(APP / "androidMain" / "AndroidManifest.xml").getroot()
        application = manifest.find("application")
        self.assertEqual("@mipmap/ic_launcher", application.get(f"{ANDROID_NS}icon"))
        self.assertEqual("@mipmap/ic_launcher_round", application.get(f"{ANDROID_NS}roundIcon"))
        for name in ("ic_launcher", "ic_launcher_round"):
            adaptive = ET.parse(ANDROID_RES / "mipmap-anydpi-v26" / f"{name}.xml").getroot()
            self.assertEqual("adaptive-icon", adaptive.tag)
            for layer in ("background", "foreground"):
                ref = adaptive.find(layer).get(f"{ANDROID_NS}drawable")
                kind, res = re.fullmatch(r"@(drawable|mipmap)/(\w+)", ref).groups()
                found = list(ANDROID_RES.glob(f"{kind}*/{res}.*"))
                self.assertTrue(found, f"{name}.xml {layer} points at missing {ref}")

    def test_android_below_api_26_gets_legacy_bitmaps(self):
        # minSdk is 24, and adaptive icons need 26.
        for density, size in LEGACY_DENSITIES.items():
            for name in ("ic_launcher", "ic_launcher_round"):
                width, height, _ = png_header(ANDROID_RES / f"mipmap-{density}" / f"{name}.png")
                self.assertEqual((size, size), (width, height), f"{density}/{name}")

    def test_ios_has_an_opaque_1024_app_icon_in_the_built_target(self):
        contents = json.loads((IOS_ICONSET / "Contents.json").read_text())
        image = next(i for i in contents["images"] if i.get("size") == "1024x1024")
        self.assertEqual("universal", image["idiom"])
        self.assertEqual("ios", image["platform"])
        width, height, colour_type = png_header(IOS_ICONSET / image["filename"])
        self.assertEqual((1024, 1024), (width, height))
        # App Store Connect rejects an icon with an alpha channel (colour types 4 and 6).
        self.assertIn(colour_type, (0, 2), "the iOS icon must not have an alpha channel")

        project = PBXPROJ.read_text()
        resources = re.search(r"/\* Resources \*/ = \{\s*isa = PBXResourcesBuildPhase;.*?files = \((.*?)\);", project, re.S)
        self.assertIn("Assets.xcassets in Resources", resources.group(1))
        self.assertEqual(2, project.count("ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;"), "Debug and Release")

    def test_desktop_icons_are_the_new_artwork(self):
        png = DESKTOP / "AppIcon.png"
        self.assertEqual((512, 512, 6), png_header(png))
        self.assertNotEqual(GHOST_SHA256, hashlib.sha256(png.read_bytes()).hexdigest())
        in_app = APP / "commonMain" / "composeResources" / "drawable" / "ic_launcher.png"
        self.assertEqual(png.read_bytes(), in_app.read_bytes(), "window/top-bar icon matches the packaged one")
        icns = (DESKTOP / "AppIcon.icns").read_bytes()
        self.assertEqual(b"icns", icns[:4])
        self.assertEqual(len(icns), struct.unpack(">I", icns[4:8])[0])
        self.assertIn(b"ic10", icns, "macOS needs the 1024 (512@2x) entry")
        ico = (DESKTOP / "AppIcon.ico").read_bytes()
        count = struct.unpack("<H", ico[4:6])[0]
        sizes = {ico[6 + 16 * i] or 256 for i in range(count)}
        self.assertTrue({16, 32, 48, 256} <= sizes, f"ico sizes {sorted(sizes)}")


if __name__ == "__main__":
    unittest.main()
