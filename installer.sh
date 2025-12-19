#!/bin/bash

# Snapchat Memories Downloader - macOS installer & runner
# This script installs dependencies and runs the orchestrator

set -e  # Exit on any error

echo "=========================================="
echo "Snapchat Memories Downloader - Installer"
echo "=========================================="
echo ""

# Colored output helpers
print_success() {
    echo "✅ $1"
}

print_error() {
    echo "❌ $1"
}

print_info() {
    echo "ℹ️  $1"
}

# Ensure we're on macOS
if [[ "$OSTYPE" != "darwin"* ]]; then
    print_error "This script only works on macOS!"
    exit 1
fi

print_info "Installation starting..."
echo ""

# 1. Install Homebrew if missing
echo "Step 1/5: Checking Homebrew..."
if ! command -v brew &> /dev/null; then
    print_info "Installing Homebrew..."
    print_info "You might be asked for your password."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    
    # Add Homebrew to PATH (Apple Silicon Macs)
    if [[ $(uname -m) == 'arm64' ]]; then
        echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile
        eval "$(/opt/homebrew/bin/brew shellenv)"
    fi
    
    print_success "Homebrew installed!"
else
    print_success "Homebrew already installed!"
fi
echo ""

# 2. Install Python3
echo "Step 2/5: Check Python3..."
if ! command -v python3 &> /dev/null; then
    print_info "Installing Python3..."
    brew install python3
    print_success "Python3 installed!"
else
    print_success "Python3 already installed!"
    python3 --version
fi
echo ""

# 3. Install ExifTool
echo "Step 3/5: Check ExifTool..."
if ! command -v exiftool &> /dev/null; then
    print_info "Installing ExifTool..."
    brew install exiftool
    print_success "ExifTool installed!"
else
    print_success "ExifTool already installed!"
fi
echo ""

# 4. Install Python libraries
echo "Step 4/5: Install Python libraries..."
print_info "Installing: requests, beautifulsoup4, Pillow..."

# Ensure pip3 is available
if ! command -v pip3 &> /dev/null; then
    print_error "pip3 not found. Please reinstall Python..."
    brew reinstall python3
fi

pip3 install --upgrade pip --quiet
pip3 install requests beautifulsoup4 Pillow --quiet

print_success "Python libraries installed!"
echo ""

# 5. Optional: Install FFmpeg for video overlay combining
echo "Step 5/5: Check FFmpeg (optional, for video overlays)..."
if ! command -v ffmpeg &> /dev/null; then
    print_info "FFmpeg not found. This is optional but needed for combining video overlays."
    read -p "Install FFmpeg? (y/n): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        brew install ffmpeg
        print_success "FFmpeg installed!"
    else
        print_info "Skipped FFmpeg installation. Videos with overlays won't be combined."
    fi
else
    print_success "FFmpeg is already installed!"
fi
echo ""

# Installation completed
print_success "Installation completed successfully!"
echo ""

# Now set up Python venv and run orchestrator
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# Create/activate venv for orchestrator if needed
if [ ! -d ".venv" ]; then
    print_info "Setting up Python virtual environment..."
    python3 -m venv .venv
fi

print_info "Activating virtual environment..."
source .venv/bin/activate

# Install Python requirements
print_info "Installing Python dependencies..."
pip install -q --upgrade pip
if [ -f "requirements.txt" ]; then
    pip install -q -r requirements.txt
fi

echo ""
echo "=========================================="
print_success "Environment ready!"
echo "=========================================="
echo ""

# Run the orchestrator with any passed arguments
print_info "Starting downloader..."
python3 run_all.py "$@"