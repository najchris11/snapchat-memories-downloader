#!/usr/bin/env python3
"""
Script to extract GPS coordinates from HTML and write them to files.
"""

import os
import sys
import re
import json
import subprocess
from bs4 import BeautifulSoup
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor, as_completed

# Configuration
import argparse

# Configuration
HTML_FILE = 'memories_history.html'
DOWNLOADED_FILES_JSON = 'downloaded_files.json'
METADATA_JSON = 'metadata.json'
DOWNLOAD_FOLDER = os.path.join(os.path.expanduser('~'), 'Downloads', 'snapchat_memories')
USE_EXIFTOOL = True
MAX_WORKERS = max(2, (os.cpu_count() or 4) // 2)

def parse_args():
    global HTML_FILE, MAX_WORKERS, DOWNLOAD_FOLDER
    parser = argparse.ArgumentParser(description="Location Metadata Extractor")
    parser.add_argument('input', nargs='?', help='Path to memories_history.html')
    parser.add_argument('--workers', type=int, help='Number of parallel workers')
    parser.add_argument('--output', type=str, help='Output folder for memories')
    args = parser.parse_args()
    
    if args.input:
        HTML_FILE = args.input
    if args.workers:
        MAX_WORKERS = args.workers
    if args.output:
        DOWNLOAD_FOLDER = args.output

parse_args()

def check_exiftool():
    """Check whether exiftool is installed."""
    try:
        subprocess.run(['exiftool', '-ver'], capture_output=True, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False

exiftool_available = check_exiftool() if USE_EXIFTOOL else False

def extract_locations_from_html(html_file):
    """Extract GPS coordinates from the HTML table."""
    if not os.path.exists(html_file):
        print(f"❌ '{html_file}' not found!")
        return []
    
    with open(html_file, 'r', encoding='utf-8') as f:
        html_content = f.read()
    
    soup = BeautifulSoup(html_content, 'html.parser')
    locations = []
    
    # Find the table
    table = soup.select_one('body > div.rightpanel > table > tbody')
    if not table:
        print("⚠️  Table not found in the HTML!")
        return locations
    
    rows = table.find_all('tr')
    
    # Pattern for coordinates: "Latitude, Longitude: 48.26275, 13.296288"
    coord_pattern = re.compile(r'Latitude,\s*Longitude:\s*([+-]?\d+\.?\d*),\s*([+-]?\d+\.?\d*)')
    
    for row in rows:
        cells = row.find_all('td')
        
        # Search all cells for coordinates
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
                break  # Only one location per line
    
    return locations

def extract_urls_from_html(html_file):
    """Extract URLs and create an index mapping."""
    if not os.path.exists(html_file):
        return []
    
    with open(html_file, 'r', encoding='utf-8') as f:
        html_content = f.read()
    
    pattern = r"downloadMemories\('(.+?)',\s*this,\s*(true|false)\)"
    matches = re.findall(pattern, html_content)
    
    return [url for url, _ in matches]

def extract_unique_id_from_url(url):
    """Extract the unique ID (mid) from the URL."""
    mid_match = re.search(r'mid=([a-zA-Z0-9\-]+)', url)
    if mid_match:
        return mid_match.group(1)
    else:
        import hashlib
        return hashlib.md5(url.encode()).hexdigest()

def parse_date_string(date_str):
    """Parse a date string into EXIF-friendly components."""
    if not date_str:
        return None
    try:
        date_cleaned = date_str.strip()
        for fmt in [
            '%Y-%m-%d %H:%M:%S %Z',
            '%Y-%m-%d %H:%M:%S',
            '%Y-%m-%d',
            '%d.%m.%Y %H:%M:%S',
            '%d.%m.%Y'
        ]:
            try:
                dt = datetime.strptime(date_cleaned.replace('UTC', '').strip(), fmt.replace(' %Z', ''))
                return dt
            except Exception:
                continue
    except Exception:
        pass
    return None

def format_exif_datetime(dt):
    return dt.strftime('%Y:%m:%d %H:%M:%S') if dt else None

def write_metadata_to_file(filepath, latitude, longitude, date_str=None):
    """Write GPS (and when available date) into the file's metadata via exiftool."""
    if not exiftool_available:
        return False

    if not os.path.exists(filepath):
        return False

    try:
        file_ext = os.path.splitext(filepath)[1].lower()
        filename = os.path.basename(filepath)

        # Skip overlay/thumbnail helper files
        if '-overlay' in filename.lower() or 'thumbnail' in filename.lower():
            return False

        # Convert to EXIF GPS format
        lat_ref = 'N' if latitude >= 0 else 'S'
        lon_ref = 'E' if longitude >= 0 else 'W'
        abs_lat = abs(latitude)
        abs_lon = abs(longitude)

        exif_dt = format_exif_datetime(parse_date_string(date_str)) if date_str else None

        # Build exiftool args once, applicable to both images and videos
        args = [
            'exiftool',
            '-overwrite_original',
            '-q',
            f'-GPSLatitude={abs_lat}',
            f'-GPSLatitudeRef={lat_ref}',
            f'-GPSLongitude={abs_lon}',
            f'-GPSLongitudeRef={lon_ref}',
        ]

        if exif_dt:
            args.extend([
                f'-DateTimeOriginal={exif_dt}',
                f'-CreateDate={exif_dt}',
                f'-ModifyDate={exif_dt}',
            ])

        # For videos, ExifTool will map these tags appropriately (QuickTime/MP4)
        args.append(filepath)

        result = subprocess.run(args, capture_output=True)
        return result.returncode == 0

    except Exception as e:
        print(f"[GPS ERROR] Failed to write for {os.path.basename(filepath)}: {e}")
        return False

def process_files_in_folder(folder_path, latitude, longitude, date_str=None):
    """Write GPS/date data for all files in a folder (extracted ZIPs)."""
    if not os.path.isdir(folder_path):
        return 0
    
    success_count = 0
    
    for root, dirs, files in os.walk(folder_path):
        for file in files:
            file_path = os.path.join(root, file)
            if file_path.lower().endswith(('.jpg', '.jpeg', '.mp4', '.mov', '.avi')):
                if write_metadata_to_file(file_path, latitude, longitude, date_str=date_str):
                    success_count += 1
    
    return success_count

def main():
    global MAX_WORKERS
    print("=" * 60)
    print("Location Metadata Extractor & Writer")
    print("=" * 60)
    print()

    for a in sys.argv[1:]:
        if a.startswith('--workers='):
            try:
                MAX_WORKERS = max(1, int(a.split('=', 1)[1]))
            except ValueError:
                pass
    
    # Check exiftool
    if USE_EXIFTOOL and not exiftool_available:
        print("❌ exiftool not found!")
        print("Install from https://exiftool.org/")
        print("Metadata will only be stored in JSON, not in files.")
        response = input("\nContinue anyway? (y/n): ")
        if response.lower() not in ['j', 'y', 'ja', 'yes']:
            return
        print()
    elif exiftool_available:
        print("✅ exiftool found - GPS data will be written to files")
        print()
    
    # Load downloaded_files.json
    if not os.path.exists(DOWNLOADED_FILES_JSON):
        print(f"❌ '{DOWNLOADED_FILES_JSON}' not found!")
        return
    
    with open(DOWNLOADED_FILES_JSON, 'r', encoding='utf-8') as f:
        downloaded_files = json.load(f)
    
    print(f"📄 Found {len(downloaded_files)} entries in downloaded_files.json")
    
    # Extract locations from HTML
    print(f"📍 Extracting GPS coordinates from '{HTML_FILE}'...")
    locations = extract_locations_from_html(HTML_FILE)
    print(f"✅ {len(locations)} GPS coordinates found")
    # Extract URLs for mapping
    urls = extract_urls_from_html(HTML_FILE)
    print(f"✅ {len(urls)} URLs found")
    print()
    
    # Create Metadata
    metadata = {}
    files_with_location = 0
    files_without_location = 0
    gps_written_count = 0
    gps_failed_count = 0
    
    tasks = []
    completed_uids = set()
    for i, url in enumerate(urls):
        unique_id = extract_unique_id_from_url(url)
        if unique_id not in downloaded_files:
            continue
        file_info = downloaded_files[unique_id]
        filename = file_info.get('filename')
        date_str = file_info.get('date')
        location = locations[i] if i < len(locations) else None
        already_written = file_info.get('metadata_written') is True

        metadata[unique_id] = {
            'filename': filename,
            'date': file_info.get('date'),
            'content_type': file_info.get('content_type'),
            'location': location
        }

        if location:
            files_with_location += 1
            if exiftool_available and not already_written:
                filepath = os.path.join(DOWNLOAD_FOLDER, filename)
                if os.path.isfile(filepath) and not filepath.lower().endswith('.png'):
                    tasks.append((unique_id, filepath, location['latitude'], location['longitude'], date_str))
                elif os.path.isdir(filepath.replace('.zip', '')):
                    folder_path = filepath.replace('.zip', '')
                    # Collect all eligible files inside folder for parallel writing
                    for root, dirs, files in os.walk(folder_path):
                        for f in files:
                            fp = os.path.join(root, f)
                            if fp.lower().endswith(('.jpg', '.jpeg', '.mp4', '.mov', '.avi')):
                                tasks.append((unique_id, fp, location['latitude'], location['longitude'], date_str))
        else:
            files_without_location += 1

    if exiftool_available and tasks:
        def _do_gps(t):
            uid, path, lat, lon, date_str = t
            ok = write_metadata_to_file(path, lat, lon, date_str=date_str)
            return (uid, path, ok)
        with ThreadPoolExecutor(max_workers=MAX_WORKERS) as ex:
            futures = [ex.submit(_do_gps, t) for t in tasks]
            for fut in as_completed(futures):
                uid, path, ok = fut.result()
                if ok:
                    gps_written_count += 1
                    completed_uids.add(uid)
                    print(f"✅ GPS written: {os.path.basename(path)}")
                else:
                    gps_failed_count += 1

    # Mark completed items so future runs can skip
    if completed_uids:
        for uid in completed_uids:
            if uid in downloaded_files:
                downloaded_files[uid]['metadata_written'] = True
        try:
            with open(DOWNLOADED_FILES_JSON, 'w', encoding='utf-8') as f:
                json.dump(downloaded_files, f, indent=2, ensure_ascii=False)
            print(f"💾 Updated '{DOWNLOADED_FILES_JSON}' with metadata_written flags")
        except Exception as e:
            print(f"⚠️  Could not update '{DOWNLOADED_FILES_JSON}': {e}")
    
    # Save metadata.json
    print()
    print(f"💾 Saving '{METADATA_JSON}'...")
    
    with open(METADATA_JSON, 'w', encoding='utf-8') as f:
        json.dump(metadata, f, indent=2, ensure_ascii=False)
    
    # Summary
    print()
    print("=" * 60)
    print("SUMMARY")
    print("=" * 60)
    print(f"📊 Total processed: {len(metadata)} files")
    print(f"📍 With GPS coordinates: {files_with_location} files")
    print(f"❌ Without GPS coordinates: {files_without_location} files")
    
    if exiftool_available:
        print()
        print(f"✅ GPS written to files: {gps_written_count}")
        if gps_failed_count > 0:
            print(f"⚠️  GPS write errors: {gps_failed_count}")
    
    print()
    print(f"✅ '{METADATA_JSON}' created successfully!")

if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print("\nCancelled by user.")
        sys.exit(130)