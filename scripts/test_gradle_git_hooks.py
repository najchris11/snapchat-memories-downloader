"""Exercise the Gradle Git-hook setup used by desktop builds."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent.parent


class GradleGitHooksTest(unittest.TestCase):
    def install_hooks(self, config):
        wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
        return subprocess.run(
            [str(ROOT / wrapper), ":composeApp:installGitHooks", "--console=plain"],
            cwd=ROOT,
            env={**os.environ, "GIT_CONFIG": str(config)},
            capture_output=True,
            text=True,
            timeout=180,
        )

    def test_config_write_failure_warns_without_blocking_desktop_build(self):
        # Android Studio's Run aborted when the Git command failed. A missing
        # parent forces the config write to fail without modifying the checkout.
        with tempfile.TemporaryDirectory() as directory:
            result = self.install_hooks(Path(directory) / "missing" / "config")

        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 0, output)
        self.assertIn("Could not configure Git hooks", output)
        self.assertIn("could not lock config file", output)

    def test_sets_hooks_path_when_config_is_writable(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "config"
            result = self.install_hooks(config)

            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("hooksPath = .githooks", config.read_text(encoding="utf-8"))
