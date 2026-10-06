"""Verify the generated simulator app, including Compose's mandatory launch key."""

from pathlib import Path
import plistlib
import sys


def verify_app(app: Path) -> None:
    with (app / "Info.plist").open("rb") as file:
        info = plistlib.load(file)
    if info.get("CADisableMinimumFrameDurationOnPhone") is not True:
        raise ValueError("generated Info.plist must set CADisableMinimumFrameDurationOnPhone to true")
    executable = info.get("CFBundleExecutable")
    if not isinstance(executable, str) or not executable or Path(executable).name != executable:
        raise ValueError("generated Info.plist must name the app executable")
    if (app / executable).stat().st_size == 0:
        raise ValueError("built app executable is empty")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: verify_ios_app.py PATH_TO_APP")
    try:
        verify_app(Path(sys.argv[1]))
    except (OSError, ValueError, plistlib.InvalidFileException) as error:
        sys.exit(f"iOS app verification failed: {error}")
    print("iOS app executable and Compose launch configuration verified")
