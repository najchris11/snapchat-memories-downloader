#!/usr/bin/env python3
"""
Script zum Erkennen und Entfernen von Duplikaten in entpackten ZIP-Ordnern
"""

import os
import hashlib
from pathlib import Path

# Konfiguration
DOWNLOAD_FOLDER = 'snapchat_memories'
DRY_RUN = True  # Auf False setzen um tatsächlich zu löschen

def calculate_file_hash(filepath):
    """Berechnet SHA256-Hash einer Datei"""
    sha256_hash = hashlib.sha256()
    try:
        with open(filepath, "rb") as f:
            for byte_block in iter(lambda: f.read(4096), b""):
                sha256_hash.update(byte_block)
        return sha256_hash.hexdigest()
    except Exception as e:
        print(f"❌ Fehler beim Hash-Berechnen für {filepath}: {e}")
        return None

def find_duplicates_in_folder(folder_path):
    """Findet Duplikate in einem Ordner basierend auf Hash"""
    files = []
    
    for item in os.listdir(folder_path):
        item_path = os.path.join(folder_path, item)
        if os.path.isfile(item_path):
            files.append(item_path)
    
    if len(files) < 2:
        return []
    
    # Berechne Hashes für alle Dateien
    file_hashes = {}
    for filepath in files:
        file_hash = calculate_file_hash(filepath)
        if file_hash:
            if file_hash not in file_hashes:
                file_hashes[file_hash] = []
            file_hashes[file_hash].append(filepath)
    
    # Finde Duplikate (Hash mit mehreren Dateien)
    duplicates = []
    for file_hash, filepaths in file_hashes.items():
        if len(filepaths) > 1:
            # Sortiere: Behalte die Datei, die zum Ordnernamen passt
            folder_name = os.path.basename(folder_path)
            
            # Extrahiere UUID/ID aus Ordnername (Format: YYYYMMDD_HHMMSS_UUID)
            folder_uuid = folder_name.split('_', 2)[-1] if '_' in folder_name else folder_name
            
            primary = None
            to_delete = []
            
            for filepath in filepaths:
                filename = os.path.basename(filepath)
                # Prüfe ob Dateiname mit Ordner-UUID beginnt
                if filename.startswith(folder_uuid):
                    primary = filepath
                else:
                    to_delete.append(filepath)
            
            # Falls kein Match mit Ordner-UUID, behalte die erste Datei
            if primary is None:
                primary = filepaths[0]
                to_delete = filepaths[1:]
            
            if to_delete:
                duplicates.append({
                    'hash': file_hash,
                    'keep': primary,
                    'delete': to_delete
                })
    
    return duplicates

def process_folders(directory, dry_run=True):
    """Verarbeitet alle Ordner und findet Duplikate"""
    if not os.path.exists(directory):
        print(f"❌ Ordner '{directory}' existiert nicht!")
        return
    
    folders_with_duplicates = []
    total_duplicates = 0
    deleted_count = 0
    
    # Durchsuche alle Unterordner
    for item in os.listdir(directory):
        item_path = os.path.join(directory, item)
        
        if os.path.isdir(item_path):
            duplicates = find_duplicates_in_folder(item_path)
            
            if duplicates:
                folders_with_duplicates.append({
                    'folder': item,
                    'path': item_path,
                    'duplicates': duplicates
                })
                
                # Zähle alle zu löschenden Dateien
                for dup in duplicates:
                    total_duplicates += len(dup['delete'])
    
    if not folders_with_duplicates:
        print("✅ Keine Duplikate gefunden!")
        return
    
    print(f"📊 {len(folders_with_duplicates)} Ordner mit Duplikaten gefunden")
    print(f"🗑️  Insgesamt {total_duplicates} Duplikate zu löschen\n")
    print("=" * 80)
    print()
    
    # Verarbeite jeden Ordner
    for folder_info in folders_with_duplicates:
        folder_name = folder_info['folder']
        duplicates = folder_info['duplicates']
        
        print(f"📁 {folder_name}/")
        print(f"   Gefunden: {len(duplicates)} Duplikat-Gruppe(n)")
        print()
        
        for dup in duplicates:
            keep_file = os.path.basename(dup['keep'])
            print(f"   ✅ BEHALTEN: {keep_file}")
            
            for delete_file in dup['delete']:
                delete_filename = os.path.basename(delete_file)
                print(f"   🗑️  LÖSCHEN:  {delete_filename}")
                
                if not dry_run:
                    try:
                        os.remove(delete_file)
                        deleted_count += 1
                        print(f"      → Gelöscht!")
                    except Exception as e:
                        print(f"      ❌ Fehler: {e}")
            
            print()
        
        print("-" * 80)
        print()
    
    # Zusammenfassung
    print("=" * 80)
    print("ZUSAMMENFASSUNG")
    print("=" * 80)
    
    if dry_run:
        print("⚠️  DRY RUN MODUS - Keine Dateien gelöscht!")
        print()
        print(f"📊 Ordner mit Duplikaten: {len(folders_with_duplicates)}")
        print(f"🗑️  Dateien zum Löschen: {total_duplicates}")
        print()
        print("💡 Um die Duplikate zu löschen:")
        print("   Setze DRY_RUN = False im Script")
    else:
        print(f"✅ Erfolgreich gelöscht: {deleted_count} Dateien")
        if deleted_count < total_duplicates:
            print(f"⚠️  Fehler bei: {total_duplicates - deleted_count} Dateien")

def main():
    print("=" * 80)
    print("Deduplicate ZIP Folder Contents")
    print("=" * 80)
    print()
    
    if DRY_RUN:
        print("⚠️  DRY RUN MODUS - Nur Vorschau, keine Änderungen")
        print()
    else:
        print("⚠️  ACHTUNG: Duplikate werden wirklich gelöscht!")
        response = input("Fortfahren? (j/n): ")
        if response.lower() not in ['j', 'y', 'ja', 'yes']:
            print("Abgebrochen.")
            return
        print()
    
    process_folders(DOWNLOAD_FOLDER, dry_run=DRY_RUN)

if __name__ == '__main__':
    main()