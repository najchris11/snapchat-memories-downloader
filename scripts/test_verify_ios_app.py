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
        self.write_plist(CADisableMinimumFrameDurationOnPhone=True)
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
                self.write_plist(**values)
                result = self.verify()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("CADisableMinimumFrameDurationOnPhone", result.stderr)

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
