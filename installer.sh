#!/bin/bash

# Snapchat Memories Downloader - Installer für Mac
# Dieses Script installiert alle notwendigen Abhängigkeiten

set -e  # Bei Fehler abbrechen

echo "=========================================="
echo "Snapchat Memories Downloader - Installer"
echo "=========================================="
echo ""

# Funktion für farbige Ausgaben
print_success() {
    echo "✅ $1"
}

print_error() {
    echo "❌ $1"
}

print_info() {
    echo "ℹ️  $1"
}

# Prüfe ob wir auf einem Mac sind
if [[ "$OSTYPE" != "darwin"* ]]; then
    print_error "Dieses Script funktioniert nur auf macOS!"
    exit 1
fi

print_info "Installation startet..."
echo ""

# 1. Homebrew installieren (falls nicht vorhanden)
echo "Schritt 1/4: Prüfe Homebrew..."
if ! command -v brew &> /dev/null; then
    print_info "Homebrew wird installiert..."
    print_info "Du wirst möglicherweise nach deinem Passwort gefragt."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    
    # Homebrew zum PATH hinzufügen (für Apple Silicon Macs)
    if [[ $(uname -m) == 'arm64' ]]; then
        echo 'eval "$(/opt/homebrew/bin/brew shellenv)"' >> ~/.zprofile
        eval "$(/opt/homebrew/bin/brew shellenv)"
    fi
    
    print_success "Homebrew installiert!"
else
    print_success "Homebrew ist bereits installiert!"
fi
echo ""

# 2. Python3 installieren
echo "Schritt 2/4: Prüfe Python3..."
if ! command -v python3 &> /dev/null; then
    print_info "Python3 wird installiert..."
    brew install python3
    print_success "Python3 installiert!"
else
    print_success "Python3 ist bereits installiert!"
    python3 --version
fi
echo ""

# 3. ExifTool installieren
echo "Schritt 3/4: Prüfe ExifTool..."
if ! command -v exiftool &> /dev/null; then
    print_info "ExifTool wird installiert..."
    brew install exiftool
    print_success "ExifTool installiert!"
else
    print_success "ExifTool ist bereits installiert!"
fi
echo ""

# 4. Python-Bibliotheken installieren
echo "Schritt 4/4: Installiere Python-Bibliotheken..."
print_info "Installiere: requests, beautifulsoup4..."

# Prüfe ob pip3 verfügbar ist
if ! command -v pip3 &> /dev/null; then
    print_error "pip3 wurde nicht gefunden. Installiere Python erneut..."
    brew reinstall python3
fi

pip3 install --upgrade pip --quiet
pip3 install requests beautifulsoup4 --quiet

print_success "Python-Bibliotheken installiert!"
echo ""

# Installation abgeschlossen
echo "=========================================="
print_success "Installation erfolgreich abgeschlossen!"
echo "=========================================="
echo ""
echo "📝 Nächste Schritte:"
echo ""
echo "1. Lade deine Snapchat Memories HTML-Datei herunter"
echo "   (von Snapchat: Einstellungen → Meine Daten → Daten herunterladen)"
echo ""
echo "2. Lege die HTML-Datei in denselben Ordner wie das"
echo "   'snapchat_downloader.py' Script"
echo ""
echo "3. Benenne die HTML-Datei um in: memories_history.html"
echo ""
echo "4. Öffne Terminal und navigiere zum Ordner:"
echo "   cd /Pfad/zum/Ordner"
echo ""
echo "5. Führe das Script aus:"
echo "   python3 snapchat_downloader.py"
echo ""
echo "=========================================="
echo ""

# Optional: Script-Ordner öffnen
read -p "Möchtest du den Downloads-Ordner jetzt öffnen? (j/n): " -n 1 -r
echo
if [[ $REPLY =~ ^[JjYy]$ ]]; then
    # Öffne den Ordner wo das Script liegt
    SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
    open "$SCRIPT_DIR"
fi

echo ""
print_success "Viel Erfolg beim Herunterladen deiner Memories! 📸"