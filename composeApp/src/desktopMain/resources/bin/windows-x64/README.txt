Drop the Windows x64 binaries here as zip files:

  exiftool.zip  — exiftool.exe (with exiftool_files/ beside it) at the zip root,
                  or inside a single top-level folder
  ffmpeg.zip    — ffmpeg.exe at the zip root, or inside a single top-level folder

ShippedToolBundlesTest installs both the way the app does; run it after replacing either.

Sources:
  exiftool: https://exiftool.org/  (Windows Executable)
  ffmpeg:   https://ffmpeg.org/download.html  (Windows Builds → essentials build)

The app installs these under %USERPROFILE%\.snapvault\bin\<tool>\<version>\ on first use.
