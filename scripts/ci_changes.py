"""Classify git diff --name-only -z input for the required PR build jobs."""

import sys


def requires_build(payload: bytes) -> bool:
    if payload and not payload.endswith(b"\0"):
        raise ValueError("expected NUL-delimited git diff paths")
    paths = payload.decode("utf-8", errors="surrogateescape").split("\0")
    return any(
        path
        and not (
            path.endswith(".md")
            or path.startswith("docs/")
            or path in {".gitignore", "LICENSE"}
        )
        for path in paths
    )


if __name__ == "__main__":
    try:
        build = requires_build(sys.stdin.buffer.read())
    except ValueError as error:
        sys.exit(str(error))
    print(f"build={'true' if build else 'false'}")
