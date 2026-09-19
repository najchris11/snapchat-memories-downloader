$ErrorActionPreference = 'Stop'

$RootDir = Resolve-Path (Join-Path $PSScriptRoot '..')
$ResourcesDir = Join-Path $RootDir 'composeApp\src\desktopMain\resources\bin\windows-x64'

# Pinned downloads, verified against upstreamSha256 in
# composeApp\src\desktopMain\resources\bin\manifest.json. A mismatch stops the script.
# ShippedToolManifestTest keeps these in step with the manifest.
$ExifUrl = 'https://sourceforge.net/projects/exiftool/files/exiftool-13.59_64.zip/download'
$ExifSha256 = '44b512b25af500724ba579d0a53c8fc5851628b692dd5e5d94ae4a15c2cba9ec'
$FfmpegUrl = 'https://github.com/GyanD/codexffmpeg/releases/download/8.1.1/ffmpeg-8.1.1-essentials_build.zip'
$FfmpegSha256 = '6f58ce889f59c311410f7d2b18895b33c03456463486f3b1ebc93d97a0f54541'

function Assert-Sha256([string]$Path, [string]$Expected) {
  $Actual = (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant()
  if ($Actual -ne $Expected) {
    throw "$(Split-Path -Leaf $Path) has SHA-256 $Actual, expected $Expected"
  }
}

# Create resources folder
New-Item -ItemType Directory -Force -Path $ResourcesDir | Out-Null

$TempDir = New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetTempPath()) -Name ([System.Guid]::NewGuid().ToString())

try {
  # 1. Download exiftool (SourceForge keeps old versions; exiftool.org removes them)
  Write-Host "Downloading ExifTool for Windows..."
  $ExifZip = Join-Path $TempDir 'exiftool_download.zip'
  Invoke-WebRequest -Uri $ExifUrl -OutFile $ExifZip -UseBasicParsing
  Assert-Sha256 $ExifZip $ExifSha256
  
  $ExifExtractPath = Join-Path $TempDir 'exiftool_extracted'
  Expand-Archive -Path $ExifZip -DestinationPath $ExifExtractPath -Force
  
  $ExifExe = Get-ChildItem -Path $ExifExtractPath -Recurse -Filter 'exiftool(-k).exe' | Select-Object -First 1
  if (-not $ExifExe) {
    throw 'exiftool executable not found in archive'
  }
  
  # Setup staging folder to zip ExifTool
  $ExifStaging = Join-Path $TempDir 'exiftool_staging'
  New-Item -ItemType Directory -Force -Path $ExifStaging | Out-Null
  Copy-Item -Force $ExifExe.FullName (Join-Path $ExifStaging 'exiftool.exe')
  
  $ExifFilesDir = Get-ChildItem -Path $ExifExtractPath -Recurse -Directory -Filter 'exiftool_files' | Select-Object -First 1
  if ($ExifFilesDir) {
    Copy-Item -Force -Recurse $ExifFilesDir.FullName (Join-Path $ExifStaging 'exiftool_files')
  }
  
  # Package exiftool.zip
  $ExifZipOut = Join-Path $ResourcesDir 'exiftool.zip'
  if (Test-Path $ExifZipOut) { Remove-Item -Force $ExifZipOut }
  Compress-Archive -Path (Join-Path $ExifStaging '*') -DestinationPath $ExifZipOut -Force

  # 2. Download ffmpeg
  Write-Host "Downloading FFmpeg for Windows..."
  $FfmpegZip = Join-Path $TempDir 'ffmpeg_download.zip'
  Invoke-WebRequest -Uri $FfmpegUrl -OutFile $FfmpegZip -UseBasicParsing
  Assert-Sha256 $FfmpegZip $FfmpegSha256
  
  $FfmpegExtractPath = Join-Path $TempDir 'ffmpeg_extracted'
  Expand-Archive -Path $FfmpegZip -DestinationPath $FfmpegExtractPath -Force
  
  $FfmpegExe = Get-ChildItem -Path $FfmpegExtractPath -Recurse -Filter 'ffmpeg.exe' | Select-Object -First 1
  if (-not $FfmpegExe) {
    throw 'ffmpeg.exe not found in archive'
  }
  
  # Setup staging folder to zip FFmpeg
  $FfmpegStaging = Join-Path $TempDir 'ffmpeg_staging'
  New-Item -ItemType Directory -Force -Path $FfmpegStaging | Out-Null
  Copy-Item -Force $FfmpegExe.FullName (Join-Path $FfmpegStaging 'ffmpeg.exe')
  
  # Package ffmpeg.zip
  $FfmpegZipOut = Join-Path $ResourcesDir 'ffmpeg.zip'
  if (Test-Path $FfmpegZipOut) { Remove-Item -Force $FfmpegZipOut }
  Compress-Archive -Path (Join-Path $FfmpegStaging '*') -DestinationPath $FfmpegZipOut -Force

  Write-Host "Windows runtimes successfully packaged under $ResourcesDir"
  Write-Host "Update archiveSha256 in bin\manifest.json for the new archives:"
  Get-FileHash -Algorithm SHA256 (Join-Path $ResourcesDir '*.zip') | Format-Table Hash, Path
} finally {
  Remove-Item -Recurse -Force $TempDir -ErrorAction SilentlyContinue
}