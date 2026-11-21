"""Source contract for the native (emulator/simulator) test workflow."""

from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/native-tests.yml"
REQUIRED = ROOT / ".github/workflows/check.yml"
INSTRUMENTED = ROOT / "composeApp/src/androidInstrumentedTest/kotlin/com/najdev/snapvault"


class NativeWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.source = WORKFLOW.read_text()

    def test_runs_both_native_suites(self):
        self.assertIn(":composeApp:connectedDebugAndroidTest", self.source)
        self.assertIn(":composeApp:iosSimulatorArm64Test", self.source)
        self.assertIn("runs-on: macos-latest", self.source)

    # The persisted-grant test needs a folder picked by hand first; on a fresh emulator it
    # can only fail, so it must be excluded by its exact class name.
    def test_excludes_only_the_test_that_needs_a_manual_grant(self):
        excluded = re.findall(r"notClass=([\w.,]+)", self.source)
        self.assertEqual(["com.najdev.snapvault.PersistedDocumentAccessTest"], excluded)
        self.assertTrue((INSTRUMENTED / "PersistedDocumentAccessTest.kt").is_file())

    # Not required until proven reliable: check.yml must not depend on these jobs.
    def test_is_not_wired_into_the_required_checks(self):
        required = REQUIRED.read_text()
        self.assertNotIn("android-instrumented", required)
        self.assertNotIn("ios-simulator", required)

    def test_the_simulator_is_chosen_rather_than_assumed(self):
        self.assertIn('--device "${{ steps.sim.outputs.udid }}"', self.source)


if __name__ == "__main__":
    unittest.main()
