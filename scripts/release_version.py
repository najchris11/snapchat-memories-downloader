#!/usr/bin/env python3
"""Works out and applies the version for a release. Used by .github/workflows/release.yml.

  release_version.py next  --commit-message MSG --tags TAG...   prints the next version
  release_version.py apply VERSION                              writes it into the build

`next` refuses a version that is not above every version already tagged. The version in
gradle.properties is only as current as the branch it is read from: develop once carried
1.0.11 after 1.0.13 had shipped, and bumping that blindly re-tags or goes backwards (D16).
"""

import argparse
import re
import sys

PROPERTIES = "gradle.properties"
BUILD_SCRIPT = "composeApp/build.gradle.kts"

_VERSION_LINE = re.compile(r"^app\.version\s*=\s*(.+?)\s*$", re.MULTILINE)
_VERSION_CODE = re.compile(r"versionCode\s*=\s*(\d+)")
_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")


class ReleaseVersionError(Exception):
    pass


def bump_kind(commit_message):
    if "MAJOR:" in commit_message:
        return "major"
    if "MINOR:" in commit_message or "FEAT:" in commit_message:
        return "minor"
    return "patch"


def _parse(version):
    core = version.split("-")[0]
    parts = core.split(".")
    if len(parts) != 3 or not all(p.isdigit() for p in parts):
        raise ReleaseVersionError(f"'{version}' is not a MAJOR.MINOR.PATCH version")
    return tuple(int(p) for p in parts)


def next_version(current, kind, tags):
    major, minor, patch = _parse(current)
    if kind == "major":
        candidate = (major + 1, 0, 0)
    elif kind == "minor":
        candidate = (major, minor + 1, 0)
    else:
        candidate = (major, minor, patch + 1)

    released = [tuple(int(g) for g in m.groups()) for m in (_TAG.match(t.strip()) for t in tags) if m]
    if released:
        latest = max(released)
        if candidate <= latest:
            latest_tag = "v%d.%d.%d" % latest
            raise ReleaseVersionError(
                f"gradle.properties says {current}, which would release v{'%d.%d.%d' % candidate}, "
                f"but {latest_tag} is already released. Bring app.version up to date with the "
                f"latest release on this branch before releasing."
            )
    return "%d.%d.%d" % candidate


def read_version(properties_path):
    with open(properties_path) as f:
        match = _VERSION_LINE.search(f.read())
    if not match:
        raise ReleaseVersionError(f"app.version not found in {properties_path}")
    return match.group(1)


def apply_version(version, properties_path, build_script_path):
    _parse(version)
    with open(properties_path) as f:
        props = f.read()
    if not _VERSION_LINE.search(props):
        raise ReleaseVersionError(f"app.version not found in {properties_path}")
    with open(properties_path, "w") as f:
        f.write(_VERSION_LINE.sub(f"app.version={version}", props, count=1))

    with open(build_script_path) as f:
        build = f.read()
    match = _VERSION_CODE.search(build)
    if match:
        build = _VERSION_CODE.sub(f"versionCode = {int(match.group(1)) + 1}", build, count=1)
        with open(build_script_path, "w") as f:
            f.write(build)


def main(argv):
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    nxt = sub.add_parser("next")
    nxt.add_argument("--commit-message", required=True)
    nxt.add_argument("--tags", nargs="*", default=[])
    app = sub.add_parser("apply")
    app.add_argument("version")
    args = parser.parse_args(argv)

    try:
        if args.command == "next":
            print(next_version(read_version(PROPERTIES), bump_kind(args.commit_message), args.tags))
        else:
            apply_version(args.version, PROPERTIES, BUILD_SCRIPT)
    except ReleaseVersionError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
