"""Source contract for required checks; complements the executable CI helper tests."""

from pathlib import Path
import re
import unittest

WORKFLOW = Path(__file__).resolve().parents[1] / ".github/workflows/check.yml"


class PrWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.source = WORKFLOW.read_text()

    def block(self, name, indent):
        match = re.search(
            rf"^{indent}{name}:\n(.*?)(?=^{indent}\S|\Z)", self.source, re.M | re.S
        )
        self.assertIsNotNone(match, f"missing {name} block")
        return match.group(1)

    # Required checks never arrive when an entire docs-only workflow is filtered out.
    def test_every_pr_starts_the_workflow(self):
        block = self.block("pull_request", "  ")
        self.assertNotIn("paths-ignore:", block)
        self.assertNotIn("paths:", block)

    def test_required_platform_jobs_report_detector_failures(self):
        for name in ["desktop", "android", "ios"]:
            with self.subTest(job=name):
                block = self.block(name, "  ")
                self.assertNotIn("continue-on-error:", block)
                self.assertIn("needs: changes", block)
                self.assertIn("always()", block)
                self.assertIn("needs.changes.result != 'success'", block)
                self.assertIn("needs.changes.outputs.build != 'false'", block)
                self.assertIn("name: Check change detection", block)
                self.assertIn("run: exit 1", block)

    def test_diff_includes_deletions_and_renames_as_nul_delimited_paths(self):
        block = self.block("changes", "  ")
        self.assertIn("fetch-depth: 0", block)
        self.assertIn("--no-renames", block)
        self.assertIn("--name-only -z", block)
        self.assertIn("shell: bash", block)
        self.assertIn("python3 scripts/ci_changes.py", block)

    def test_ios_builds_and_verifies_the_complete_unsigned_simulator_app(self):
        block = self.block("ios", "  ")
        for requirement in [
            "linkDebugFrameworkIosSimulatorArm64",
            "xcodebuild", "-project iosApp/iosApp.xcodeproj", "-scheme iosApp",
            "-sdk iphonesimulator", "generic/platform=iOS Simulator",
            "ARCHS=arm64",
            "CODE_SIGNING_ALLOWED=NO", "python3 scripts/verify_ios_app.py",
        ]:
            self.assertIn(requirement, block)
        self.assertNotIn("|| true", block)


if __name__ == "__main__":
    unittest.main()
