#!/usr/bin/env python3
"""
Script to detect and remove duplicates in extracted ZIP folders.
"""

import os
import sys
import hashlib
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

# Configuration
import argparse

DOWNLOAD_FOLDER = os.path.join(os.path.expanduser('~'), 'Downloads', 'snapchat_memories')
DRY_RUN = True  # Set to False to actually delete files
MAX_WORKERS = max(2, (os.cpu_count() or 4) // 2)

def parse_args():
    parser = argparse.ArgumentParser(description="Remove duplicate files in memories")
    parser.add_argument('--output', type=str, help='Folder containing memories')
    parser.add_argument('--dry-run', action='store_true', help='Preview only, do not delete files')
    parser.add_argument('--no-dry-run', action='store_true', help='Force deletion even if DRY_RUN is True')
    parser.add_argument('--workers', type=int, help='Number of parallel workers (threads)')
    parser.add_argument('--auto-confirm', action='store_true', help='Skip confirmation prompts (for automated runs)')
    args = parser.parse_args()

    # Start from module defaults
    download_folder = DOWNLOAD_FOLDER
    dry_run = DRY_RUN
    max_workers = MAX_WORKERS

    if args.output:
        download_folder = args.output
    if args.dry_run:
        dry_run = True
    elif args.no_dry_run:
        dry_run = False
    if args.workers:
        max_workers = max(1, args.workers)

    return download_folder, dry_run, max_workers, args.auto_confirm

DOWNLOAD_FOLDER, DRY_RUN, MAX_WORKERS, AUTO_CONFIRM = parse_args()
def calculate_file_hash(filepath):
    """Calculate SHA256 hash for a file."""
    sha256_hash = hashlib.sha256()
    try:
        with open(filepath, "rb") as f:
            for byte_block in iter(lambda: f.read(4096), b""):
                sha256_hash.update(byte_block)
        return sha256_hash.hexdigest()
    except Exception as e:
        print(f"[ERROR] Error while computing hash for {filepath}: {e}")
        return None

def find_duplicates_in_folder(folder_path):
    """Find duplicates in a folder based on file hashes."""
    files = []
    
    for item in os.listdir(folder_path):
        item_path = os.path.join(folder_path, item)
        if os.path.isfile(item_path):
            files.append(item_path)
    
    if len(files) < 2:
        return []
    
    # Calculate hashes for all files
    file_hashes = {}
    for filepath in files:
        file_hash = calculate_file_hash(filepath)
        if file_hash:
            if file_hash not in file_hashes:
                file_hashes[file_hash] = []
            file_hashes[file_hash].append(filepath)
    
    # Find duplicates (hash present for multiple files)
    duplicates = []
    for file_hash, filepaths in file_hashes.items():
        if len(filepaths) > 1:
            # Keep the file whose name matches the folder name
            folder_name = os.path.basename(folder_path)
            
            # Extract UUID/ID from folder name (format: YYYYMMDD_HHMMSS_UUID)
            folder_uuid = folder_name.split('_', 2)[-1] if '_' in folder_name else folder_name
            
            primary = None
            to_delete = []
            
            for filepath in filepaths:
                filename = os.path.basename(filepath)
                # Check if filename starts with the folder UUID
                if filename.startswith(folder_uuid):
                    primary = filepath
                else:
                    to_delete.append(filepath)
            
            # If no match with folder UUID, keep the first file
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
    """Process all folders and find duplicates (parallelize scanning)."""
    if not os.path.exists(directory):
        print(f"[ERROR] Folder '{directory}' does not exist!")
        return

    subfolders = [f for f in os.listdir(directory) if os.path.isdir(os.path.join(directory, f))]
    results = []

    def _scan_one(folder_name):
        folder_path = os.path.join(directory, folder_name)
        duplicates = find_duplicates_in_folder(folder_path)
        return {'folder': folder_name, 'path': folder_path, 'duplicates': duplicates}

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as ex:
        futures = [ex.submit(_scan_one, f) for f in subfolders]
        for fut in as_completed(futures):
            results.append(fut.result())

    folders_with_duplicates = [r for r in results if r['duplicates']]
    if not folders_with_duplicates:
        print("[OK] No duplicates found!")
        return

    total_duplicates = sum(len(dup['delete']) for r in folders_with_duplicates for dup in r['duplicates'])
    deleted_count = 0

    print(f"[STATS] Found {len(folders_with_duplicates)} folder(s) with duplicates")
    print(f"[INFO] Total duplicates to delete: {total_duplicates}\n")
    print("=" * 80)
    print()

    for folder_info in folders_with_duplicates:
        folder_name = folder_info['folder']
        duplicates = folder_info['duplicates']
        print(f"[FOLDER] {folder_name}/")
        print(f"   Found: {len(duplicates)} duplicate group(s)")
        print()
        for dup in duplicates:
            keep_file = os.path.basename(dup['keep'])
            print(f"   [KEEP] {keep_file}")
            for delete_file in dup['delete']:
                delete_filename = os.path.basename(delete_file)
                print(f"   [DELETE] {delete_filename}")
                if not dry_run:
                    try:
                        os.remove(delete_file)
                        deleted_count += 1
                        print(f"      -> Deleted!")
                    except Exception as e:
                        print(f"      [ERROR] {e}")
            print()
        print("-" * 80)
        print()

    print("=" * 80)
    print("SUMMARY")
    print("=" * 80)

    if dry_run:
        print("[WARN] DRY RUN MODE - No files deleted!")
        print()
        print(f"[STATS] Folders with duplicates: {len(folders_with_duplicates)}")
        print(f"[INFO] Files to delete: {total_duplicates}")
        print()
        print("[TIP] To delete the duplicates:")
        print("   Set DRY_RUN = False in the script")
    else:
        print(f"[OK] Successfully deleted: {deleted_count} files")
        if deleted_count < total_duplicates:
            print(f"[WARN] Errors on: {total_duplicates - deleted_count} files")

def main():
    print("=" * 80)
    print("Deduplicate ZIP Folder Contents")
    print("=" * 80)
    print()
    
    if DRY_RUN:
        print("[WARN] DRY RUN MODE - Preview only, no changes")
        print()
    else:
        print("[WARN] WARNING: Duplicates will actually be deleted!")
        if not AUTO_CONFIRM:
            response = input("Continue? (y/n): ")
            if response.lower() not in ['j', 'y', 'ja', 'yes']:
                print("Cancelled.")
                return
        print()
    
    process_folders(DOWNLOAD_FOLDER, dry_run=DRY_RUN)

if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print("\nCancelled by user.")
        sys.exit(130)