# Privacy Policy

SnapVault is designed to process personal photo and video archives locally on your device.

## Data Processing & Network Access

- **No accounts, analytics, telemetry, or crash reporting:** SnapVault collects zero personal data, usage metrics, or diagnostic reports.
- **ZIP Import mode (100% offline):** Extracting Snapchat `mydata~*.zip` archives, writing EXIF/QuickTime timestamps and GPS coordinates, compositing `-main` and `-overlay` media, and deduplicating files all execute locally on your machine with zero app network requests. A document provider you choose may independently retrieve cloud-hosted source documents.
- **Legacy Link Import mode:** When importing an older link-based `memories_history.html` or `memories_history.json` export, SnapVault connects solely to the media download URLs listed inside your own export file in order to download your files to your chosen output folder.
- **Optional update checks:** Only clicking **Check for updates** in Settings contacts GitHub’s Releases API to compare the latest stable release with your installed version. There are no automatic checks, downloads, or installations. GitHub receives ordinary connection information such as your IP address and a SnapVault version user-agent; no export contents or media are sent.
- **External links:** Clicking optional support or documentation links opens your system browser to those external pages.

## Local Storage

- SnapVault stores application preferences locally on your device and writes `vault_index.json` inside the output library directory you choose so the Library view can display favorite, overlay, and GPS badges without re-reading every media file on launch.
- **Android sources and working library:** Selected ZIP documents are opened read-only through Android's document provider and streamed without caching whole archives. SnapVault requests persistent read access so selected sources can be reopened. Small selected JSON/HTML history files may be copied into app-private storage for parsing. Imported media and its index live in SnapVault's app-owned working library; uninstalling SnapVault removes that library.
- **Explicit Android gallery copies (Android 10+):** Clicking **Save to gallery** creates a separate image/video copy through MediaStore. SnapVault publishes it only after copying succeeds and attempts to remove its own pending copy if copying fails or is cancelled. The library original and selected source archives remain unchanged. Gallery copies remain outside SnapVault's app storage and may be synced by your photo app according to its settings. SnapVault requests no broad gallery-reading or storage permissions.
- **Explicit iOS Photos copies:** Clicking **Save to Apple Photos** requests add-only Photos permission and creates an image/video asset from the selected local file. SnapVault does not request permission to read your photo library and does not delete the library original. Photos and iCloud may sync the saved copy according to your system settings. Once a Photos transaction is submitted it may finish even if you leave the preview.
- Offline Memories map: Verified coordinates are also saved in the local index so the Library can draw them on bundled public-domain land outlines. Older entries without coordinates are left unmapped. No map tiles or coordinate lookups are requested over the network.
- Offline desktop timezone lookup: When precise GPS metadata is written, bundled boundary data can resolve a unique timezone and write a matching local capture time and EXIF offset. Coordinates are not sent to a service.
- Embedded GPS tags: When GPS tagging is enabled, coordinates from your Snapchat export are written into your local output files' EXIF/QuickTime metadata. Disable **Precise time + GPS matching** on the Dashboard if you prefer date-only metadata.

## Contact

Questions or security/privacy reports can be opened on [GitHub Issues](https://github.com/najchris11/snapchat-memories-downloader/issues) or emailed to `support@naj-dev.com`.
