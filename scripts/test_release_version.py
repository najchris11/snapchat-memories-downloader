"""Tests for release_version.py. Run: python3 -m unittest discover -s scripts -p 'test_*.py'"""

import os
import tempfile
import unittest

import release_version as rv


class BumpKindTest(unittest.TestCase):
    def test_markers_choose_the_bump(self):
        self.assertEqual(rv.bump_kind("MAJOR: drop Java 17"), "major")
        self.assertEqual(rv.bump_kind("MINOR: add a setting"), "minor")
        self.assertEqual(rv.bump_kind("FEAT: add a setting"), "minor")
        self.assertEqual(rv.bump_kind("fix: a crash"), "patch")


class NextVersionTest(unittest.TestCase):
    def test_patch_minor_and_major(self):
        self.assertEqual(rv.next_version("1.2.3", "patch", []), "1.2.4")
        self.assertEqual(rv.next_version("1.2.3", "minor", []), "1.3.0")
        self.assertEqual(rv.next_version("1.2.3", "major", []), "2.0.0")

    def test_a_pre_release_label_is_dropped_before_bumping(self):
        self.assertEqual(rv.next_version("1.2.3-beta", "patch", []), "1.2.4")

    # D16: develop carried app.version=1.0.11 while v1.0.12 and v1.0.13 were already released.
    # The old workflow bumped whatever gradle.properties said, so a release from that state
    # would have tried to tag v1.0.12 a second time, or, after a force, ship a lower version
    # than the one users already have.
    def test_a_version_already_released_is_refused(self):
        with self.assertRaises(rv.ReleaseVersionError) as raised:
            rv.next_version("1.0.11", "patch", ["v1.0.11", "v1.0.12", "v1.0.13"])
        self.assertIn("v1.0.13", str(raised.exception))

    def test_a_version_below_the_latest_release_is_refused_even_if_untagged(self):
        with self.assertRaises(rv.ReleaseVersionError):
            rv.next_version("1.0.9", "minor", ["v1.0.13", "v1.2.0"])

    def test_the_latest_release_itself_is_refused(self):
        with self.assertRaises(rv.ReleaseVersionError):
            rv.next_version("1.0.12", "patch", ["v1.0.13"])

    def test_the_next_version_above_every_release_is_accepted(self):
        self.assertEqual(rv.next_version("1.0.13", "patch", ["v1.0.12", "v1.0.13", "not-a-version"]), "1.0.14")


class ApplyVersionTest(unittest.TestCase):
    def test_writes_the_version_and_increments_the_android_version_code(self):
        with tempfile.TemporaryDirectory() as root:
            props = os.path.join(root, "gradle.properties")
            build = os.path.join(root, "build.gradle.kts")
            with open(props, "w") as f:
                f.write("kotlin.code.style=official\napp.version=1.0.13\nother=1\n")
            with open(build, "w") as f:
                f.write("android {\n    versionCode = 12\n}\n")

            rv.apply_version("1.0.14", props, build)

            with open(props) as f:
                self.assertEqual(f.read(), "kotlin.code.style=official\napp.version=1.0.14\nother=1\n")
            with open(build) as f:
                self.assertIn("versionCode = 13", f.read())

    def test_a_properties_file_without_a_version_is_an_error(self):
        with tempfile.TemporaryDirectory() as root:
            props = os.path.join(root, "gradle.properties")
            with open(props, "w") as f:
                f.write("kotlin.code.style=official\n")
            with self.assertRaises(rv.ReleaseVersionError):
                rv.read_version(props)


if __name__ == "__main__":
    unittest.main()
