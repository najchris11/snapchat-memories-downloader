#!/usr/bin/env python3
"""
Script zum Extrahieren von GPS-Koordinaten aus HTML und Schreiben in Dateien
"""

import os
import re
import json
import subprocess
from bs4 import BeautifulSoup
from datetime import datetime

# Konfiguration
HTML_FILE = 'memories_history.html'
DOWNLOADED_FILES_JSON = 'downloaded_files.json'
METADATA_JSON = 'metadata.json'
DOWNLOAD_FOLDER = 'snapchat_memories'
USE_EXIFTOOL = True

def check_exiftool():
    """Prüft, ob exiftool installiert ist"""
    try:
        subprocess.run(['exiftool', '-ver'], capture_output=True, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False

exiftool_available = check_exiftool() if USE_EXIFTOOL else False

def extract_locations_from_html(html_file):
    """Extrahiert GPS-Koordinaten aus der HTML-Tabelle"""
    if not os.path.exists(html_file):
        print(f"❌ '{html_file}' nicht gefunden!")
        return []
    
    with open(html_file, 'r', encoding='utf-8') as f:
        html_content = f.read()
    
    soup = BeautifulSoup(html_content, 'html.parser')
    locations = []
    
    # Suche alle Tabellen
    table = soup.select_one('body > div.rightpanel > table > tbody')
    if not table:
        print("⚠️  Tabelle nicht gefunden im HTML!")
        return locations
    
    rows = table.find_all('tr')
    
    # Pattern für Koordinaten: "Latitude, Longitude: 48.26275, 13.296288"
    coord_pattern = re.compile(r'Latitude,\s*Longitude:\s*([+-]?\d+\.?\d*),\s*([+-]?\d+\.?\d*)')
    
    for row in rows:
        cells = row.find_all('td')
        
        # Durchsuche alle Zellen nach Koordinaten
        for cell in cells:
            text = cell.get_text(strip=True)
            match = coord_pattern.search(text)
            
            if match:
                latitude = float(match.group(1))
                longitude = float(match.group(2))
                locations.append({
                    'latitude': latitude,
                    'longitude': longitude
                })
                break  # Nur eine Location pro Zeile
    
    return locations

def extract_urls_from_html(html_file):
    """Extrahiert URLs und erstellt Mapping zu Index"""
    if not os.path.exists(html_file):
        return []
    
    with open(html_file, 'r', encoding='utf-8') as f:
        html_content = f.read()
    
    pattern = r"downloadMemories\('(.+?)',\s*this,\s*(true|false)\)"
    matches = re.findall(pattern, html_content)
    
    return [url for url, _ in matches]

def extract_unique_id_from_url(url):
    """Extrahiert die eindeutige ID (mid) aus der URL"""
    mid_match = re.search(r'mid=([a-zA-Z0-9\-]+)', url)
    if mid_match:
        return mid_match.group(1)
    else:
        import hashlib
        return hashlib.md5(url.encode()).hexdigest()

def write_gps_to_file(filepath, latitude, longitude):
    """Schreibt GPS-Koordinaten in die EXIF-Daten der Datei"""
    if not exiftool_available:
        return False
    
    if not os.path.exists(filepath):
        return False
    
    try:
        file_ext = os.path.splitext(filepath)[1].lower()
        filename = os.path.basename(filepath)
        
        # Überspringe spezielle Dateien
        if '-overlay' in filename.lower() or 'thumbnail' in filename.lower():
            return False
        
        # Konvertiere zu EXIF GPS-Format
        # GPSLatitude und GPSLongitude benötigen Ref (N/S, E/W)
        lat_ref = 'N' if latitude >= 0 else 'S'
        lon_ref = 'E' if longitude >= 0 else 'W'
        
        abs_lat = abs(latitude)
        abs_lon = abs(longitude)
        
        if file_ext in ['.jpg', '.jpeg', '.png']:
            result = subprocess.run([
                'exiftool',
                '-overwrite_original',
                '-q',
                f'-GPSLatitude={abs_lat}',
                f'-GPSLatitudeRef={lat_ref}',
                f'-GPSLongitude={abs_lon}',
                f'-GPSLongitudeRef={lon_ref}',
                filepath
            ], capture_output=True)
            
            return result.returncode == 0
            
        elif file_ext in ['.mp4', '.mov', '.avi']:
            result = subprocess.run([
                'exiftool',
                '-overwrite_original',
                '-q',
                f'-GPSLatitude={abs_lat}',
                f'-GPSLatitudeRef={lat_ref}',
                f'-GPSLongitude={abs_lon}',
                f'-GPSLongitudeRef={lon_ref}',
                filepath
            ], capture_output=True)
            
            return result.returncode == 0
        
        return False
        
    except Exception as e:
        print(f"[GPS ERROR] Fehler beim Schreiben für {os.path.basename(filepath)}: {e}")
        return False

def process_files_in_folder(folder_path, latitude, longitude):
    """Schreibt GPS-Daten für alle Dateien in einem Ordner (entpackte ZIPs)"""
    if not os.path.isdir(folder_path):
        return 0
    
    success_count = 0
    
    for root, dirs, files in os.walk(folder_path):
        for file in files:
            file_path = os.path.join(root, file)
            if file_path.lower().endswith(('.jpg', '.jpeg', '.png', '.mp4', '.mov', '.avi')):
                if write_gps_to_file(file_path, latitude, longitude):
                    success_count += 1
    
    return success_count

def main():
    print("=" * 60)
    print("Location Metadata Extractor & Writer")
    print("=" * 60)
    print()
    
    # Prüfe exiftool
    if USE_EXIFTOOL and not exiftool_available:
        print("❌ exiftool nicht gefunden!")
        print("Installation: https://exiftool.org/")
        print("Metadaten werden nur in JSON gespeichert, nicht in Dateien.")
        response = input("\nTrotzdem fortfahren? (j/n): ")
        if response.lower() not in ['j', 'y', 'ja', 'yes']:
            return
        print()
    elif exiftool_available:
        print("✅ exiftool gefunden - GPS-Daten werden in Dateien geschrieben")
        print()
    
    # Lade downloaded_files.json
    if not os.path.exists(DOWNLOADED_FILES_JSON):
        print(f"❌ '{DOWNLOADED_FILES_JSON}' nicht gefunden!")
        return
    
    with open(DOWNLOADED_FILES_JSON, 'r', encoding='utf-8') as f:
        downloaded_files = json.load(f)
    
    print(f"📄 {len(downloaded_files)} Einträge in downloaded_files.json gefunden")
    
    # Extrahiere Locations aus HTML
    print(f"📍 Extrahiere GPS-Koordinaten aus '{HTML_FILE}'...")
    locations = extract_locations_from_html(HTML_FILE)
    print(f"✅ {len(locations)} GPS-Koordinaten gefunden")
    
    # Extrahiere URLs für Mapping
    urls = extract_urls_from_html(HTML_FILE)
    print(f"✅ {len(urls)} URLs gefunden")
    print()
    
    # Erstelle Metadata
    metadata = {}
    files_with_location = 0
    files_without_location = 0
    gps_written_count = 0
    gps_failed_count = 0
    
    for i, url in enumerate(urls):
        unique_id = extract_unique_id_from_url(url)
        
        # Prüfe ob Datei heruntergeladen wurde
        if unique_id not in downloaded_files:
            continue
        
        file_info = downloaded_files[unique_id]
        filename = file_info.get('filename')
        
        # GPS-Koordinaten hinzufügen (falls vorhanden)
        location = locations[i] if i < len(locations) else None
        
        metadata[unique_id] = {
            'filename': filename,
            'date': file_info.get('date'),
            'content_type': file_info.get('content_type'),
            'location': location
        }
        
        if location:
            files_with_location += 1
            
            # Schreibe GPS in Datei
            if exiftool_available:
                filepath = os.path.join(DOWNLOAD_FOLDER, filename)
                
                # Prüfe ob es eine Datei oder ein Ordner ist (entpackte ZIP)
                if os.path.isfile(filepath):
                    if write_gps_to_file(filepath, location['latitude'], location['longitude']):
                        gps_written_count += 1
                        print(f"✅ GPS geschrieben: {filename}")
                    else:
                        gps_failed_count += 1
                        print(f"⚠️  GPS fehlgeschlagen: {filename}")
                
                elif os.path.isdir(filepath.replace('.zip', '')):
                    # Entpackter ZIP-Ordner
                    folder_path = filepath.replace('.zip', '')
                    count = process_files_in_folder(folder_path, location['latitude'], location['longitude'])
                    gps_written_count += count
                    print(f"✅ GPS geschrieben für {count} Dateien in: {os.path.basename(folder_path)}/")
        else:
            files_without_location += 1
    
    # Speichere metadata.json
    print()
    print(f"💾 Speichere '{METADATA_JSON}'...")
    
    with open(METADATA_JSON, 'w', encoding='utf-8') as f:
        json.dump(metadata, f, indent=2, ensure_ascii=False)
    
    # Zusammenfassung
    print()
    print("=" * 60)
    print("ZUSAMMENFASSUNG")
    print("=" * 60)
    print(f"📊 Gesamt verarbeitet: {len(metadata)} Dateien")
    print(f"📍 Mit GPS-Koordinaten: {files_with_location} Dateien")
    print(f"❌ Ohne GPS-Koordinaten: {files_without_location} Dateien")
    
    if exiftool_available:
        print()
        print(f"✅ GPS in Dateien geschrieben: {gps_written_count}")
        if gps_failed_count > 0:
            print(f"⚠️  GPS-Schreibfehler: {gps_failed_count}")
    
    print()
    print(f"✅ '{METADATA_JSON}' erfolgreich erstellt!")

if __name__ == '__main__':
    main()