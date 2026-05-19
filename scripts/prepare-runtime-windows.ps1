$ErrorActionPreference = 'Stop'

$RootDir = Resolve-Path (Join-Path $PSScriptRoot '..')
$RuntimeDir = Join-Path $RootDir 'runtime'
$PythonDir = Join-Path $RuntimeDir 'python'
$ToolsDir = Join-Path $RuntimeDir 'tools\win32'

$PyVersion = '3.11.15'
$PyRelease = '20260510'
$Arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'aarch64' } else { 'x86_64' }
$PyPlatform = "${Arch}-pc-windows-msvc"
$PyZip = "cpython-$PyVersion+$PyRelease-$PyPlatform-install_only.tar.gz"
$PyUrl = "https://github.com/astral-sh/python-build-standalone/releases/download/$PyRelease/$PyZip"

New-Item -ItemType Directory -Force -Path $PythonDir | Out-Null
New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null

$TempDir = New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetTempPath()) -Name ([System.Guid]::NewGuid().ToString())

function Assert-Sha256 {
  param(
    [Parameter(Mandatory = $true)][string]$FilePath,
    [string]$Expected
  )

  if ([string]::IsNullOrWhiteSpace($Expected)) {
    Write-Host "WARN: No SHA256 provided for $([System.IO.Path]::GetFileName($FilePath)); skipping checksum verification."
    return
  }

  $Actual = (Get-FileHash -Path $FilePath -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($Actual -ne $Expected.ToLowerInvariant()) {
    throw "SHA256 mismatch for $FilePath. Expected: $Expected; Actual: $Actual"
  }
}

Write-Host "Downloading Python runtime: $PyUrl"
$PyZipPath = Join-Path $TempDir $PyZip
Invoke-WebRequest -Uri $PyUrl -OutFile $PyZipPath
Assert-Sha256 -FilePath $PyZipPath -Expected $env:PYTHON_SHA256

Write-Host "Extracting Python runtime..."
Remove-Item -Recurse -Force (Join-Path $PythonDir '*') -ErrorAction SilentlyContinue
tar -xzf $PyZipPath -C $PythonDir --strip-components=1

$PythonExe = Join-Path $PythonDir 'python.exe'
& $PythonExe -m ensurepip --upgrade
& $PythonExe -m pip install -q --upgrade pip
& $PythonExe -m pip install -q -r (Join-Path $RootDir 'requirements.txt')

Write-Host "Downloading exiftool..."
$ExifUrl = 'https://sourceforge.net/projects/exiftool/files/exiftool-13.58_64.zip/download'
$ExifZip = Join-Path $TempDir 'exiftool.zip'
Invoke-WebRequest -Uri $ExifUrl -OutFile $ExifZip
Assert-Sha256 -FilePath $ExifZip -Expected $env:EXIFTOOL_SHA256
Expand-Archive -Path $ExifZip -DestinationPath (Join-Path $TempDir 'exiftool') -Force

$ExifExe = Get-ChildItem -Path (Join-Path $TempDir 'exiftool') -Recurse -Filter 'exiftool(-k).exe' | Select-Object -First 1
if (-not $ExifExe) {
  throw 'exiftool executable not found in archive'
}
Copy-Item -Force $ExifExe.FullName (Join-Path $ToolsDir 'exiftool.exe')

$ExifFilesDir = Get-ChildItem -Path (Join-Path $TempDir 'exiftool') -Recurse -Directory -Filter 'exiftool_files' | Select-Object -First 1
if (-not $ExifFilesDir) {
  throw 'exiftool_files directory not found in archive'
}
Copy-Item -Force -Recurse $ExifFilesDir.FullName (Join-Path $ToolsDir 'exiftool_files')

Write-Host "Downloading ffmpeg..."
$FfmpegUrl = 'https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip'
$FfmpegZip = Join-Path $TempDir 'ffmpeg.zip'
Invoke-WebRequest -Uri $FfmpegUrl -OutFile $FfmpegZip
Assert-Sha256 -FilePath $FfmpegZip -Expected $env:FFMPEG_SHA256
Expand-Archive -Path $FfmpegZip -DestinationPath (Join-Path $TempDir 'ffmpeg') -Force

$FfmpegExe = Get-ChildItem -Path (Join-Path $TempDir 'ffmpeg') -Recurse -Filter 'ffmpeg.exe' | Select-Object -First 1
if (-not $FfmpegExe) {
  throw 'ffmpeg executable not found in archive'
}
Copy-Item -Force $FfmpegExe.FullName (Join-Path $ToolsDir 'ffmpeg.exe')

Write-Host "Runtime prepared at $RuntimeDir"