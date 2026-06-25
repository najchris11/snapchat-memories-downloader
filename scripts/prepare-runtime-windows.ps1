$ErrorActionPreference = 'Stop'

$RootDir = Resolve-Path (Join-Path $PSScriptRoot '..')
$ResourcesDir = Join-Path $RootDir 'composeApp\src\desktopMain\resources\bin\windows-x64'

# Create resources folder
New-Item -ItemType Directory -Force -Path $ResourcesDir | Out-Null

$TempDir = New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetTempPath()) -Name ([System.Guid]::NewGuid().ToString())

try {
  # 1. Download exiftool
  Write-Host "Downloading ExifTool for Windows..."
  $ExifUrl = 'https://sourceforge.net/projects/exiftool/files/exiftool-13.58_64.zip/download'
  $ExifZip = Join-Path $TempDir 'exiftool_download.zip'
  Invoke-WebRequest -Uri $ExifUrl -OutFile $ExifZip
  
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
  $FfmpegUrl = 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip'
  $FfmpegZip = Join-Path $TempDir 'ffmpeg_download.zip'
  Invoke-WebRequest -Uri $FfmpegUrl -OutFile $FfmpegZip
  
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
} finally {
  Remove-Item -Recurse -Force $TempDir -ErrorAction SilentlyContinue
}