"""Tests for automated GitHub release notes generation."""

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_notes  # noqa: E402


class ReleaseNotesTest(unittest.TestCase):
    def test_previous_release_tag_picks_highest_semver_below_current(self):
        tags = ["v1.0.9", "v1.0.14", "v1.0.15", "v1.0.2", "not-a-tag"]
        self.assertEqual(
            "v1.0.14",
            release_notes.previous_release_tag("v1.0.15", tags),
        )

    def test_previous_release_tag_returns_none_when_first_release(self):
        self.assertIsNone(release_notes.previous_release_tag("v1.0.0", ["v1.0.0"]))
        self.assertIsNone(release_notes.previous_release_tag("v1.0.0", []))

    def test_render_notes_includes_per_os_installers_and_first_launch_guidance(self):
        body = release_notes.render_release_notes(
            tag="v1.0.16",
            previous_tag="v1.0.15",
            commit_subjects=[
                "chore: release v1.0.16",
                "perf: exclude foreign-OS tool archives from desktop installers",
                "fix(android): wire metadata fallback callbacks during combine",
            ],
            repo="najchris11/snapchat-memories-downloader",
        )
        self.assertIn("SnapVault-1.0.16-macos-arm64.dmg", body)
        self.assertIn("SnapVault-1.0.16-macos-x64.dmg", body)
        self.assertIn("SnapVault-1.0.16-windows-x64.msi", body)
        self.assertIn("SnapVault-1.0.16-linux-x64.deb", body)
        self.assertIn("Privacy & Security", body)
        self.assertIn("Open Anyway", body)
        self.assertIn("More info", body)
        self.assertIn("Run anyway", body)
        self.assertIn("ffmpeg", body)
        self.assertIn("perl", body)
        self.assertIn("SHA256SUMS.txt", body)
        self.assertIn("https://snapvault.naj-dev.com/tutorial", body)
        # Release bump commits are filtered out of user-facing highlights.
        self.assertNotIn("chore: release v1.0.16", body)
        self.assertIn("perf: exclude foreign-OS tool archives from desktop installers", body)
        self.assertIn(
            "https://github.com/najchris11/snapchat-memories-downloader/compare/v1.0.15...v1.0.16",
            body,
        )

    def test_render_notes_handles_empty_commit_list_and_no_previous_tag(self):
        body = release_notes.render_release_notes(
            tag="v1.0.0",
            previous_tag=None,
            commit_subjects=["chore: release v1.0.0"],
            repo="najchris11/snapchat-memories-downloader",
        )
        self.assertIn("SnapVault-1.0.0-macos-arm64.dmg", body)
        self.assertNotIn("/compare/", body)
        self.assertNotIn("chore: release v1.0.0", body)

    def test_render_notes_rejects_malformed_tag(self):
        with self.assertRaises(ValueError):
            release_notes.render_release_notes(
                tag="1.0.16",
                previous_tag=None,
                commit_subjects=[],
                repo="najchris11/snapchat-memories-downloader",
            )


if __name__ == "__main__":
    unittest.main()
