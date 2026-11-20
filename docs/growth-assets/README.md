# Draft distribution assets

All three MP4s are 1080×1920, H.264, 24 fps, 18 seconds, silent, and ready for owner review.
They use actual Compose screenshots and generated sample images, never personal exports.
No clips have been posted or submitted to any service.

- `backup-before-2027.mp4`: Snapchat's January 2027 archiving change, full export, second copy.
- `captions-and-dates.mp4`: actual overlay compositor output; generated JPEG's EXIF verified.
- `free-local-library.mp4`: desktop platforms, no processing paywall, local review.

These are new current-UI demos, rather than cuts of the older walkthrough. Add narration or
licensed music if desired before posting. Safe caption drafts:

1. “Make a copy while you have time. Snapchat says some Memories will be archived starting
   January 2027 — not automatically deleted. Request your full export, restore a local
   library with SnapVault, then keep a second verified copy.”
2. “A photo and a detached caption become one viewable memory. This generated sample ran
   through SnapVault's actual compositor; capture time, UTC offset and GPS were verified on
   the combined JPEG. Keep original unmerged files if you want clean copies too.”
3. “Free desktop Snapchat Memories backup, with no processing paywall or SnapVault account.
   Modern ZIP imports run locally. Legacy history imports download the media linked in your
   export. macOS, Windows and Linux: https://snapvault.naj-dev.com.”

Policy source, checked November 6, 2026:
https://help.snapchat.com/hc/en-us/articles/41291271694228-How-do-I-manage-my-Memories-storage

Reproduce the overlay proof with `scripts/render-growth-assets.sh <empty-output-directory>`
(Perl, unzip, FFmpeg, Java/Gradle required). It uses portable Perl source from the already
bundled ExifTool distribution. Recreate the vertical drafts with:

```
python3 scripts/growth_clips.py /path/to/snapvault-web/public/screenshots /new/output/directory
```

The renderer refuses existing outputs. The demo JPEG's GPS location is a synthetic test
value; the landscape drawing does not depict that location. The GIF cannot carry EXIF;
the verification concerns the actual JPEG output linked beside it on the tutorial page.
