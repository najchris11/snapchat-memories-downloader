#!/usr/bin/env python3
"""
Script to combine main images/videos with their overlays in extracted ZIP folders.
This merges the base image with the transparent overlay PNG on top.
For videos, it uses FFmpeg to overlay the PNG.
"""

import os
import sys
import shutil
import subprocess
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed

try:
    from PIL import Image, ImageOps
    PILLOW_AVAILABLE = True
except ImportError:
    PILLOW_AVAILABLE = False
    print("⚠️  Pillow not installed - image combining disabled")
    print("   Run: pip install Pillow")

# Configuration
import argparse

DOWNLOAD_FOLDER = os.path.join(os.path.expanduser('~'), 'Downloads', 'snapchat_memories')
DRY_RUN = False  # Set to True for preview only
DELETE_FOLDER_AFTER = True  # Delete the folder after combining (keeps only the combined image/video)
KEEP_ORIGINALS = False  # Keep original main and overlay files in the folder
USE_EXIFTOOL = True  # Copy metadata from main image to combined image
PROCESS_VIDEOS = True  # Process video files with FFmpeg
MAX_WORKERS = max(2, (os.cpu_count() or 4) // 2)

def parse_args():
    parser = argparse.ArgumentParser(description="Combine overlays with memories")
    parser.add_argument('--output', type=str, help='Folder containing memories')
    parser.add_argument('--dry-run', action='store_true', help='Preview only, do not write files or delete folders')
    parser.add_argument('--no-dry-run', action='store_true', help='Force actual combine even if DRY_RUN is True')
    parser.add_argument('--workers', type=int, help='Number of parallel workers (threads)')
    args = parser.parse_args()

    # Start from module-level defaults
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

    return {
        "download_folder": download_folder,
        "dry_run": dry_run,
        "max_workers": max_workers,
    }

config = parse_args()
DOWNLOAD_FOLDER = config["download_folder"]
DRY_RUN = config["dry_run"]
MAX_WORKERS = config["max_workers"]
def check_exiftool():
    """Checks if exiftool is installed"""
    try:
        subprocess.run(['exiftool', '-ver'], capture_output=True, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False

def check_ffmpeg():
    """Checks if ffmpeg is installed"""
    try:
        subprocess.run(['ffmpeg', '-version'], capture_output=True, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False

exiftool_available = check_exiftool() if USE_EXIFTOOL else False
ffmpeg_available = check_ffmpeg() if PROCESS_VIDEOS else False

def find_overlay_folders(directory):
    """Finds all folders containing main + overlay image pairs"""
    overlay_folders = []
    
    if not os.path.exists(directory):
        print(f"❌ Directory '{directory}' does not exist!")
        return overlay_folders
    
    for item in os.listdir(directory):
        item_path = os.path.join(directory, item)
        
        if os.path.isdir(item_path):
            # Look for main and overlay files
            main_file = None
            overlay_file = None
            
            for file in os.listdir(item_path):
                file_lower = file.lower()
                if '-main.' in file_lower or '_main.' in file_lower:
                    main_file = os.path.join(item_path, file)
                elif '-overlay.' in file_lower or '_overlay.' in file_lower:
                    overlay_file = os.path.join(item_path, file)
            
            if main_file and overlay_file:
                overlay_folders.append({
                    'folder': item,
                    'path': item_path,
                    'main': main_file,
                    'overlay': overlay_file
                })
    
    return overlay_folders

def is_video_file(filepath):
    """Check if file is a video based on extension"""
    video_extensions = ['.mp4', '.mov', '.avi', '.mkv', '.webm', '.m4v']
    return os.path.splitext(filepath)[1].lower() in video_extensions

def is_image_file(filepath):
    """Check if file is an image based on extension"""
    image_extensions = ['.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp']
    return os.path.splitext(filepath)[1].lower() in image_extensions

def combine_images(main_path, overlay_path, output_path):
    """Combines main image with overlay PNG"""
    if not PILLOW_AVAILABLE:
        print("   ❌ Pillow not available for image combining")
        return False
    
    try:
        # Open and orient the main image according to EXIF (prevents overlay misalignment)
        main_img = ImageOps.exif_transpose(Image.open(main_path))
        if main_img.mode != 'RGBA':
            main_img = main_img.convert('RGBA')

        # Open and orient the overlay
        overlay_img = ImageOps.exif_transpose(Image.open(overlay_path))
        if overlay_img.mode != 'RGBA':
            overlay_img = overlay_img.convert('RGBA')

        # Resize overlay to match main image if needed
        if overlay_img.size != main_img.size:
            overlay_img = overlay_img.resize(main_img.size, Image.Resampling.LANCZOS)

        # Composite the images
        combined = Image.alpha_composite(main_img, overlay_img)
        
        # Convert back to RGB for JPG output (if needed)
        output_ext = os.path.splitext(output_path)[1].lower()
        if output_ext in ['.jpg', '.jpeg']:
            combined = combined.convert('RGB')
        
        # Save the combined image
        if output_ext in ['.jpg', '.jpeg']:
            combined.save(output_path, 'JPEG', quality=95)
        else:
            combined.save(output_path, 'PNG')
        
        return True
        
    except Exception as e:
        print(f"   ❌ Error combining images: {e}")
        return False

def combine_video_with_overlay(video_path, overlay_path, output_path):
    """Combines video with overlay PNG using FFmpeg"""
    if not ffmpeg_available:
        print("   ❌ FFmpeg not available for video combining")
        return False
    
    try:
        # Use scale2ref to force the overlay to match the displayed video dimensions
        # Ensure overlay keeps alpha (format=rgba) before overlaying
        filter_complex = (
            '[1:v]format=rgba[ov];'
            '[ov][0:v]scale2ref=main_w:main_h[ovr][base];'
            '[base][ovr]overlay=0:0:format=auto'
        )

        result = subprocess.run([
            'ffmpeg',
            '-y',  # Overwrite output
            '-i', video_path,  # Input video
            '-i', overlay_path,  # Input overlay
            '-filter_complex', filter_complex,
            '-c:a', 'copy',  # Copy audio stream
            '-c:v', 'libx264',  # Encode video with H.264
            '-preset', 'medium',  # Encoding preset
            '-crf', '18',  # Quality (lower = better, 18-23 is good)
            '-pix_fmt', 'yuv420p',  # Compatibility
            output_path
        ], capture_output=True, text=True)
        
        if result.returncode == 0:
            return True
        else:
            # Show more helpful error info
            stderr = result.stderr
            if len(stderr) > 300:
                stderr = stderr[:300] + "..."
            print(f"   ❌ FFmpeg error: {stderr}")
            return False
        
    except Exception as e:
        print(f"   ❌ Error combining video: {e}")
        return False

def copy_metadata(source_path, dest_path):
    """Copies EXIF metadata from source to destination using exiftool"""
    if not exiftool_available:
        return False
    
    try:
        result = subprocess.run([
            'exiftool',
            '-overwrite_original',
            '-q',
            '-TagsFromFile', source_path,
            '-all:all',
            dest_path
        ], capture_output=True)
        return result.returncode == 0
    except Exception:
        return False

def process_folders(directory, dry_run=True):
    """Processes all overlay folders and combines images/videos"""
    overlay_folders = find_overlay_folders(directory)
    if not overlay_folders:
        print("✅ No overlay folders found to process!")
        return

    image_folders = [f for f in overlay_folders if is_image_file(f['main'])]
    video_folders = [f for f in overlay_folders if is_video_file(f['main'])]

    print(f"📊 Found {len(overlay_folders)} folders with overlays")
    print(f"   📷 Images: {len(image_folders)}")
    print(f"   🎬 Videos: {len(video_folders)}")
    print()

    if video_folders and not ffmpeg_available:
        print("⚠️  FFmpeg not found - video overlays will be skipped")
        print("   Install FFmpeg: brew install ffmpeg (mac) or apt install ffmpeg (linux)")
        print()

    if image_folders and not PILLOW_AVAILABLE:
        print("⚠️  Pillow not found - image overlays will be skipped")
        print("   Install Pillow: pip install Pillow")
        print()

    print("=" * 80)
    print()

    def _process_one(folder_info):
        folder_name = folder_info['folder']
        folder_path = folder_info['path']
        main_file = folder_info['main']
        overlay_file = folder_info['overlay']

        main_ext = os.path.splitext(main_file)[1]
        output_filename = folder_name + main_ext
        output_path = os.path.join(directory, output_filename)

        is_video = is_video_file(main_file)
        file_type = "🎬" if is_video else "📷"

        # Collect output logs per task to avoid interleaved printing
        logs = []
        logs.append(f"{file_type} {folder_name}/")
        logs.append(f"   Main:    {os.path.basename(main_file)}")
        logs.append(f"   Overlay: {os.path.basename(overlay_file)}")
        logs.append(f"   Output:  {output_filename}")

        if dry_run:
            if is_video and not ffmpeg_available:
                logs.append("   ⏭️  [DRY RUN] Would skip (FFmpeg not available)")
                return (0, 1, 0, logs)
            elif not is_video and not PILLOW_AVAILABLE:
                logs.append("   ⏭️  [DRY RUN] Would skip (Pillow not available)")
                return (0, 1, 0, logs)
            else:
                logs.append("   ⏭️  [DRY RUN] Would combine and create output")
                return (1, 0, 0, logs)
        else:
            if is_video:
                if ffmpeg_available:
                    ok = combine_video_with_overlay(main_file, overlay_file, output_path)
                else:
                    logs.append("   ⏭️  Skipped (FFmpeg not available)")
                    return (0, 1, 0, logs)
            else:
                if PILLOW_AVAILABLE:
                    ok = combine_images(main_file, overlay_file, output_path)
                else:
                    logs.append("   ⏭️  Skipped (Pillow not available)")
                    return (0, 1, 0, logs)

            if ok:
                logs.append("   ✅ Combined successfully!")
                if exiftool_available:
                    if copy_metadata(main_file, output_path):
                        logs.append("   📋 Metadata copied")
                    else:
                        logs.append("   ⚠️  Could not copy metadata")
                if DELETE_FOLDER_AFTER and not KEEP_ORIGINALS:
                    try:
                        shutil.rmtree(folder_path)
                        logs.append("   🗑️  Folder deleted")
                    except Exception as e:
                        logs.append(f"   ⚠️  Could not delete folder: {e}")
                return (1, 0, 0, logs)
            else:
                logs.append("   ❌ Error while combining")
                return (0, 0, 1, logs)

    success_count = 0
    error_count = 0
    skipped_count = 0

    with ThreadPoolExecutor(max_workers=MAX_WORKERS) as ex:
        futures = [ex.submit(_process_one, f) for f in overlay_folders]
        for fut in as_completed(futures):
            s, sk, e, logs = fut.result()
            success_count += s
            skipped_count += sk
            error_count += e
            for line in logs:
                print(line)
            print()

    print("=" * 80)
    print("SUMMARY")
    print("=" * 80)
    if dry_run:
        print("⚠️  DRY RUN MODE - No changes made!")
        print()
        print(f"📊 Folders to process: {len(overlay_folders)}")
        print(f"   📷 Images: {len(image_folders)}")
        print(f"   🎬 Videos: {len(video_folders)}")
        if skipped_count > 0:
            print(f"   ⏭️  Would skip: {skipped_count}")
        print()
        print("💡 To combine the overlays:")
        print("   Set DRY_RUN = False in the script")
    else:
        print(f"✅ Successfully combined: {success_count} files")
        if skipped_count > 0:
            print(f"⏭️  Skipped: {skipped_count} files")
        if error_count > 0:
            print(f"❌ Errors: {error_count} files")

def main():
    print("=" * 80)
    print("Combine Overlay Images & Videos")
    print("=" * 80)
    print()
    
    # Show tool availability
    print("Tool Status:")
    if PILLOW_AVAILABLE:
        print("  ✅ Pillow - Image combining enabled")
    else:
        print("  ❌ Pillow - Image combining disabled (pip install Pillow)")
    
    if ffmpeg_available:
        print("  ✅ FFmpeg - Video combining enabled")
    else:
        print("  ❌ FFmpeg - Video combining disabled (brew install ffmpeg)")
    
    if exiftool_available:
        print("  ✅ exiftool - Metadata will be preserved")
    else:
        print("  ⚠️  exiftool - Metadata will not be copied")
    print()
    
    if not PILLOW_AVAILABLE and not ffmpeg_available:
        print("❌ No combining tools available!")
        print("   Install at least one: pip install Pillow  or  brew install ffmpeg")
        return
    
    if DRY_RUN:
        print("⚠️  DRY RUN MODE - Preview only, no changes")
        print()
    else:
        print("⚠️  WARNING: This will combine files and delete original folders!")
        response = input("Continue? (y/n): ")
        if response.lower() not in ['y', 'yes']:
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

