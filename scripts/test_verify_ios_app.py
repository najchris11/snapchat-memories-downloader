"""Checks the built iOS app's launch requirements without needing Apple frameworks."""

from pathlib import Path
import plistlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("verify_ios_app.py")


class VerifyIosAppTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.app = Path(self.temp.name) / "iosApp.app"
        self.app.mkdir()
        self.write_plist(CADisableMinimumFrameDurationOnPhone=True, CFBundleDisplayName="SnapVault",
                         UIFileSharingEnabled=True, LSSupportsOpeningDocumentsInPlace=True)
        (self.app / "iosApp").write_bytes(b"executable")

    def write_plist(self, **values):
        values["CFBundleExecutable"] = "iosApp"
        (self.app / "Info.plist").write_bytes(plistlib.dumps(values, fmt=plistlib.FMT_BINARY))

    def verify(self):
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(self.app)], capture_output=True, text=True
        )

    def test_complete_app_with_launch_key_passes(self):
        self.assertEqual(0, self.verify().returncode)

    # Compose aborts on launch if the key is absent. A successful xcodebuild does not prove
    # it made it into the generated plist; the old INFOPLIST_KEY_* setting omitted it.
    def test_missing_or_false_launch_key_fails(self):
        for values in [{}, {"CADisableMinimumFrameDurationOnPhone": False}]:
            with self.subTest(values=values):
                self.write_plist(CFBundleDisplayName="SnapVault", UIFileSharingEnabled=True,
                                 LSSupportsOpeningDocumentsInPlace=True, **values)
                result = self.verify()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("CADisableMinimumFrameDurationOnPhone", result.stderr)

    # The home screen showed "iosApp": with no display name, iOS falls back to CFBundleName,
    # which is PRODUCT_NAME, which is the Xcode target name.
    def test_app_not_named_snapvault_on_the_home_screen_fails(self):
        for values in [{}, {"CFBundleDisplayName": "iosApp"}]:
            with self.subTest(values=values):
                self.write_plist(CADisableMinimumFrameDurationOnPhone=True, UIFileSharingEnabled=True,
                                 LSSupportsOpeningDocumentsInPlace=True, **values)
                result = self.verify()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("CFBundleDisplayName", result.stderr)

    # The working library lives in Documents; without both keys the Files app cannot show it,
    # and the user has no way to reach their imported media outside SnapVault.
    def test_library_not_visible_in_the_files_app_fails(self):
        for missing in ["UIFileSharingEnabled", "LSSupportsOpeningDocumentsInPlace"]:
            with self.subTest(missing=missing):
                values = {"CADisableMinimumFrameDurationOnPhone": True, "CFBundleDisplayName": "SnapVault",
                          "UIFileSharingEnabled": True, "LSSupportsOpeningDocumentsInPlace": True}
                del values[missing]
                self.write_plist(**values)
                result = self.verify()
                self.assertNotEqual(0, result.returncode)
                self.assertIn(missing, result.stderr)

    def test_missing_or_empty_executable_fails(self):
        executable = self.app / "iosApp"
        executable.unlink()
        self.assertNotEqual(0, self.verify().returncode)
        executable.touch()
        self.assertNotEqual(0, self.verify().returncode)

    def test_missing_or_invalid_plist_fails(self):
        plist = self.app / "Info.plist"
        plist.unlink()
        self.assertNotEqual(0, self.verify().returncode)
        plist.write_text("not a plist")
        self.assertNotEqual(0, self.verify().returncode)


if __name__ == "__main__":
    unittest.main()
