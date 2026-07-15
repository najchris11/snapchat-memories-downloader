Linux bundled binaries.

  exiftool.zip  — built by scripts/prepare-runtime-linux.sh (gitignored; CI builds it fresh).
                  Contains a wrapper script + the ExifTool Perl distribution; runs via the
                  system perl. Must contain the exiftool executable at the zip root.

FFmpeg is intentionally NOT bundled on Linux: a dynamically-linked binary copied from a
distro package fails on machines missing ffmpeg's shared libraries — exactly the machines
a bundled fallback would exist for. The app resolves ffmpeg from the system PATH instead;
users install it with their package manager (e.g. sudo apt install ffmpeg).

The app extracts bundled zips to ~/.snapvault/bin/ on first use and sets +x automatically.
