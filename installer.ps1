# Snapchat Memories Downloader - Windows installer & runner
# PowerShell script to prepare environment and run orchestrator

$ErrorActionPreference = 'Stop'

function Write-Success($msg) { Write-Host "`u2705 $msg" -ForegroundColor Green }
function Write-ErrorMsg($msg) { Write-Host "`u274C $msg" -ForegroundColor Red }
function Write-Info($msg) { Write-Host "`u2139  $msg" -ForegroundColor Cyan }

Write-Host "=========================================="
Write-Host "Snapchat Memories Downloader - Installer (Windows)"
Write-Host "=========================================="
Write-Host ""

# Ensure we're on Windows
if ($env:OS -notmatch 'Windows') {
  Write-ErrorMsg "This script is for Windows only."
  exit 1
}

Write-Info "Installation starting..."
Write-Host ""

# Helper to check command availability
function Test-Command($name) {
  return [bool](Get-Command $name -ErrorAction SilentlyContinue)
}

# 1. Ensure Python 3.10+
Write-Host "Step 1/5: Checking Python..."
$pythonExe = $null
if (Test-Command 'py') { $pythonExe = 'py' }
elseif (Test-Command 'python') { $pythonExe = 'python' }
elseif (Test-Command 'python3') { $pythonExe = 'python3' }

function Get-PythonVersion($exe) {
  try {
    if ($exe -eq 'py') {
      $out = & py -3 -c "import sys; print(sys.version)"
    } else {
      $out = & $exe -c "import sys; print(sys.version)"
    }
    return $out
  } catch { return $null }
}

$pyVersionStr = if ($pythonExe) { Get-PythonVersion $pythonExe } else { $null }

if (-not $pyVersionStr) {
  Write-Info "Python not found. Attempting install via winget (if available)..."
  if (Test-Command 'winget') {
    try {
      winget install -e --id Python.Python.3 -h --source winget
      $pythonExe = 'py'
      Write-Success "Python installed via winget."
    } catch {
      Write-ErrorMsg "Winget Python install failed. Please install Python from https://www.python.org/downloads/ and rerun this script."
      exit 1
    }
  } else {
    Write-ErrorMsg "Winget not available. Install Python from https://www.python.org/downloads/ and rerun."
    exit 1
  }
} else {
  Write-Success "Python detected: $pyVersionStr"
}

# 2. Ensure ExifTool (required)
Write-Host "Step 2/5: Checking ExifTool (required)..."
if (-not (Test-Command 'exiftool')) {
  if (Test-Command 'winget') {
    try {
      winget install -e --id PhilHarvey.ExifTool -h --source winget
      Write-Success "ExifTool installed via winget."
    } catch {
      Write-ErrorMsg "Failed to install ExifTool automatically. Please install from https://exiftool.org/ and rerun."
      exit 1
    }
  } else {
    Write-ErrorMsg "Winget not available. Install ExifTool from https://exiftool.org/ and rerun."
    exit 1
  }
} else {
  Write-Success "ExifTool already installed."
}

# 3. FFmpeg (required)
Write-Host "Step 3/5: Checking FFmpeg (required)..."
if (-not (Test-Command 'ffmpeg')) {
  if (Test-Command 'winget') {
    try {
      # Try common package ids
      winget install -e --id Gyan.FFmpeg -h --source winget
      Write-Success "FFmpeg installed via winget."
    } catch {
      try {
        winget install -e --id FFmpeg.FFmpeg -h --source winget
        Write-Success "FFmpeg installed via winget."
      } catch {
        Write-ErrorMsg "Failed to install FFmpeg automatically. Install from https://www.gyan.dev/ffmpeg/builds/ and rerun."
        exit 1
      }
    }
  } else {
    Write-ErrorMsg "Winget not available. Install FFmpeg from https://www.gyan.dev/ffmpeg/builds/ and rerun."
    exit 1
  }
} else {
  Write-Success "FFmpeg already installed."
}

# 4. Set up venv and Python deps
Write-Host "Step 4/5: Python virtual environment & requirements..."
$scriptRoot = (Get-Location).Path
$venvPath = Join-Path $scriptRoot '.venv'

if (-not (Test-Path $venvPath)) {
  Write-Info "Creating virtual environment..."
  if ($pythonExe -eq 'py') {
    py -3 -m venv .venv
  } else {
    & $pythonExe -m venv .venv
  }
}

Write-Info "Activating virtual environment..."
$venvActivate = Join-Path $venvPath 'Scripts/Activate.ps1'
. $venvActivate

Write-Info "Upgrading pip and installing requirements..."
python -m pip install -q --upgrade pip
if (Test-Path (Join-Path $scriptRoot 'requirements.txt')) {
  python -m pip install -q -r requirements.txt
}
Write-Success "Python dependencies installed."

# 5. Done
Write-Host "Step 5/5: Finalizing..."
Write-Success "Environment ready!"
Write-Host ""
Write-Info "Environment setup complete. Use the GUI to start workflows."
exit 0
