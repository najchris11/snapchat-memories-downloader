"""Regression tests for the required PR checks' changed-file filter."""

from pathlib import Path
import subprocess
import sys
import unittest

SCRIPT = Path(__file__).with_name("ci_changes.py")


class CiChangesTest(unittest.TestCase):
    def classify(self, *paths):
        payload = b"".join(path.encode() + b"\0" for path in paths)
        return subprocess.run(
            [sys.executable, str(SCRIPT)], input=payload, capture_output=True, check=True
        ).stdout.decode().strip()

    # Workflow-level paths-ignore left docs-only PRs waiting for checks that never ran.
    # Report a skip decision within the workflow, so the required jobs can finish as skipped.
    def test_docs_only_changes_skip_builds(self):
        self.assertEqual(
            "build=false",
            self.classify("README.md", "docs/MOBILE_PLAN.md", ".gitignore", "LICENSE"),
        )

    def test_code_and_mixed_changes_require_builds(self):
        for paths in [
            ("composeApp/src/commonMain/App.kt",),
            ("README.md", "iosApp/iosApp/Info.plist"),
            (".github/workflows/check.yml",),
            ("scripts/ci_changes.py",),
        ]:
            with self.subTest(paths=paths):
                self.assertEqual("build=true", self.classify(*paths))

    def test_empty_diff_skips_builds(self):
        self.assertEqual("build=false", self.classify())

    def test_nul_delimiters_preserve_spaces_and_newlines_in_paths(self):
        self.assertEqual("build=true", self.classify("src/README.md\nApp.kt"))
        self.assertEqual("build=false", self.classify("docs/a file\nname.md"))

    def test_malformed_diff_fails_instead_of_silently_skipping(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT)], input=b"README.md", capture_output=True
        )
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"", result.stdout)


if __name__ == "__main__":
    unittest.main()
