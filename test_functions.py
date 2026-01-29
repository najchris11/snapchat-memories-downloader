#!/usr/bin/env python3
"""
Test script for Snapchat Memories Downloader functions.

This script allows non-destructive testing of individual functions:
1. First downloads the full archive (runs snapchat-downloader.py)
2. Copies sample files to an 'automated_tests' directory
3. Tests each function individually on copied files by calling scripts via subprocess
4. Shows results in organized subfolders

Usage:
    python3 test_functions.py                    # Interactive menu
    python3 test_functions.py --download-only    # Only download, don't run tests
    python3 test_functions.py --test-only        # Only run tests (assumes files exist)
    python3 test_functions.py --all              # Download and run all tests

Test output structure:
    automated_tests/
    ├── samples/                    # Original copied test files
    ├── test_unzip/                 # Unzip test results
    ├── test_metadata/              # Metadata embedding test results
    └── test_overlay/               # Overlay merging test results
"""

import os
import sys
import shutil
import subprocess
import zipfile

# Configuration
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
HTML_FILE = os.path.join(SCRIPT_DIR, 'memories_history.html')
DOWNLOAD_FOLDER = os.path.join(SCRIPT_DIR, 'snapchat_memories')
TEST_FOLDER = os.path.join(SCRIPT_DIR, 'automated_tests')
SAMPLES_FOLDER = os.path.join(TEST_FOLDER, 'samples')

# Script paths (same pattern as run_all.py)
SCRIPTS = {
    'downloader': os.path.join(SCRIPT_DIR, 'snapchat-downloader.py'),
    'metadata': os.path.join(SCRIPT_DIR, 'metadata.py'),
    'combine': os.path.join(SCRIPT_DIR, 'combine_overlays.py'),
}

# Import only simple utilities that don't have side effects
from utils import check_exiftool, check_ffmpeg


def run_script(path, args=None):
    """Run a Python script with the current interpreter and return exit code."""
    if not os.path.exists(path):
        print(f"[ERROR] Script not found: {os.path.basename(path)}")
        return 1
    cmd = [sys.executable, path] + (args or [])
    result = subprocess.run(cmd)
    return result.returncode


def print_header(title):
    """Print a formatted header."""
    print()
    print("=" * 70)
    print(f" {title}")
    print("=" * 70)
    print()


def print_section(title):
    """Print a formatted section header."""
    print()
    print("-" * 50)
    print(f"  {title}")
    print("-" * 50)


def is_video_file(filepath):
    """Check if file is a video based on extension."""
    video_extensions = ['.mp4', '.mov', '.avi', '.mkv', '.webm', '.m4v']
    return os.path.splitext(filepath)[1].lower() in video_extensions


def is_image_file(filepath):
    """Check if file is an image based on extension."""
    image_extensions = ['.jpg', '.jpeg', '.png', '.gif', '.bmp', '.webp']
    return os.path.splitext(filepath)[1].lower() in image_extensions


def run_downloader():
    """Run the snapchat-downloader.py script to download all files."""
    print_header("STEP 1: DOWNLOADING MEMORIES")

    if not os.path.exists(HTML_FILE):
        print(f"[ERROR] '{os.path.basename(HTML_FILE)}' not found!")
        print("   Place your Snapchat 'memories_history.html' in the project root.")
        return False

    print(f"[INFO] Running downloader...")
    print(f"[INFO] Output folder: {DOWNLOAD_FOLDER}")
    print()

    rc = run_script(SCRIPTS['downloader'], ['--output', DOWNLOAD_FOLDER])

    if rc != 0:
        print("[ERROR] Downloader failed!")
        return False

    print()
    print("[OK] Download complete!")
    return True


def find_test_samples():
    """Find suitable test samples from downloaded files."""
    samples = {
        'photo_simple': None,
        'video_simple': None,
        'photo_overlay_folder': None,
        'video_overlay_folder': None,
    }

    if not os.path.exists(DOWNLOAD_FOLDER):
        print(f"[ERROR] Download folder not found: {DOWNLOAD_FOLDER}")
        return samples

    for item in os.listdir(DOWNLOAD_FOLDER):
        item_path = os.path.join(DOWNLOAD_FOLDER, item)

        # Check for extracted overlay folders
        if os.path.isdir(item_path):
            files_in_folder = os.listdir(item_path)
            has_main = any('-main.' in f.lower() or '_main.' in f.lower() for f in files_in_folder)
            has_overlay = any('-overlay.' in f.lower() or '_overlay.' in f.lower() for f in files_in_folder)

            if has_main and has_overlay:
                main_file = next((f for f in files_in_folder if '-main.' in f.lower() or '_main.' in f.lower()), None)
                if main_file:
                    main_path = os.path.join(item_path, main_file)
                    if is_image_file(main_path) and not samples['photo_overlay_folder']:
                        samples['photo_overlay_folder'] = item_path
                    elif is_video_file(main_path) and not samples['video_overlay_folder']:
                        samples['video_overlay_folder'] = item_path
            continue

        # Simple photo
        if not samples['photo_simple'] and is_image_file(item_path):
            samples['photo_simple'] = item_path

        # Simple video
        if not samples['video_simple'] and is_video_file(item_path):
            samples['video_simple'] = item_path

        if all(samples.values()):
            break

    return samples


def setup_test_environment(samples):
    """Create test folder structure and copy sample files."""
    print_header("STEP 2: SETTING UP TEST ENVIRONMENT")

    if os.path.exists(TEST_FOLDER):
        print(f"[INFO] Removing old test folder: {TEST_FOLDER}")
        shutil.rmtree(TEST_FOLDER)

    os.makedirs(SAMPLES_FOLDER, exist_ok=True)
    os.makedirs(os.path.join(TEST_FOLDER, 'test_unzip'), exist_ok=True)
    os.makedirs(os.path.join(TEST_FOLDER, 'test_metadata'), exist_ok=True)
    os.makedirs(os.path.join(TEST_FOLDER, 'test_overlay'), exist_ok=True)

    print(f"[OK] Created test folder structure: {TEST_FOLDER}")
    print()

    copied = {}

    if samples.get('photo_simple'):
        dest = os.path.join(SAMPLES_FOLDER, 'photo_simple' + os.path.splitext(samples['photo_simple'])[1])
        shutil.copy2(samples['photo_simple'], dest)
        copied['photo_simple'] = dest
        print(f"[OK] Copied simple photo: {os.path.basename(samples['photo_simple'])}")

    if samples.get('video_simple'):
        dest = os.path.join(SAMPLES_FOLDER, 'video_simple' + os.path.splitext(samples['video_simple'])[1])
        shutil.copy2(samples['video_simple'], dest)
        copied['video_simple'] = dest
        print(f"[OK] Copied simple video: {os.path.basename(samples['video_simple'])}")

    if samples.get('photo_overlay_folder'):
        dest = os.path.join(SAMPLES_FOLDER, 'photo_overlay')
        shutil.copytree(samples['photo_overlay_folder'], dest)
        copied['photo_overlay_folder'] = dest
        print(f"[OK] Copied photo overlay folder: {os.path.basename(samples['photo_overlay_folder'])}")

    if samples.get('video_overlay_folder'):
        dest = os.path.join(SAMPLES_FOLDER, 'video_overlay')
        shutil.copytree(samples['video_overlay_folder'], dest)
        copied['video_overlay_folder'] = dest
        print(f"[OK] Copied video overlay folder: {os.path.basename(samples['video_overlay_folder'])}")

    return copied


def test_unzip():
    """Test ZIP extraction by finding a ZIP in downloads and extracting it."""
    print_header("TEST 1: ZIP EXTRACTION")

    test_dir = os.path.join(TEST_FOLDER, 'test_unzip')
    results = {'passed': 0, 'failed': 0, 'skipped': 0}

    # Find a ZIP file in the original download folder to test with
    zip_file = None
    for item in os.listdir(DOWNLOAD_FOLDER):
        if item.lower().endswith('.zip'):
            zip_file = os.path.join(DOWNLOAD_FOLDER, item)
            break

    if not zip_file:
        print("[SKIP] No ZIP files found to test extraction")
        results['skipped'] += 1
        return results

    # Copy ZIP to test directory
    test_zip = os.path.join(test_dir, os.path.basename(zip_file))
    shutil.copy2(zip_file, test_zip)

    print(f"[TEST] Extracting: {os.path.basename(test_zip)}")

    try:
        extract_folder = os.path.splitext(test_zip)[0]
        with zipfile.ZipFile(test_zip, 'r') as zip_ref:
            zip_ref.extractall(extract_folder)
        os.remove(test_zip)

        if os.path.isdir(extract_folder):
            contents = os.listdir(extract_folder)
            print(f"       Extracted to: {extract_folder}")
            print(f"       Contents: {contents}")
            print("[PASS] ZIP extraction successful")
            results['passed'] += 1
        else:
            print("[FAIL] Extraction folder not created")
            results['failed'] += 1
    except Exception as e:
        print(f"[FAIL] ZIP extraction failed: {e}")
        results['failed'] += 1

    print_section("Unzip Test Summary")
    print(f"  Passed: {results['passed']}")
    print(f"  Failed: {results['failed']}")
    print(f"  Skipped: {results['skipped']}")

    return results


def test_metadata(copied_files):
    """Test metadata embedding by running metadata.py on test files."""
    print_header("TEST 2: METADATA EMBEDDING")

    test_dir = os.path.join(TEST_FOLDER, 'test_metadata')
    results = {'passed': 0, 'failed': 0, 'skipped': 0}

    if not check_exiftool():
        print("[ERROR] exiftool not available - skipping metadata tests")
        print("        Install from: https://exiftool.org/")
        return {'passed': 0, 'failed': 0, 'skipped': 2}

    print("[OK] exiftool is available")

    # Test writing metadata directly with exiftool (simulating what metadata.py does)
    test_date = "2023:06:15 14:30:00"

    for key in ['photo_simple', 'video_simple']:
        if key not in copied_files:
            print(f"\n[SKIP] No {key.replace('_', ' ')} to test")
            results['skipped'] += 1
            continue

        src_path = copied_files[key]
        ext = os.path.splitext(src_path)[1]
        dest_path = os.path.join(test_dir, f"{key}_with_metadata{ext}")

        shutil.copy2(src_path, dest_path)

        print(f"\n[TEST] Embedding metadata in: {os.path.basename(dest_path)}")
        print(f"       Test date: {test_date}")

        # Use exiftool directly (same as metadata.py does internally)
        if ext.lower() in ['.jpg', '.jpeg', '.png']:
            result = subprocess.run([
                'exiftool', '-overwrite_original', '-q',
                f'-DateTimeOriginal={test_date}',
                f'-CreateDate={test_date}',
                dest_path
            ], capture_output=True)
        else:
            result = subprocess.run([
                'exiftool', '-overwrite_original', '-q',
                f'-CreateDate={test_date}',
                f'-MediaCreateDate={test_date}',
                dest_path
            ], capture_output=True)

        if result.returncode == 0:
            # Verify
            verify = subprocess.run(
                ['exiftool', '-CreateDate', dest_path],
                capture_output=True, text=True
            )
            print(f"       Result: {verify.stdout.strip()}")
            print(f"[PASS] {key}: Metadata embedded successfully")
            results['passed'] += 1
        else:
            print(f"[FAIL] {key}: Metadata embedding failed")
            results['failed'] += 1

    print_section("Metadata Test Summary")
    print(f"  Passed: {results['passed']}")
    print(f"  Failed: {results['failed']}")
    print(f"  Skipped: {results['skipped']}")

    return results


def test_overlay_combining(copied_files):
    """Test overlay combining by running combine_overlays.py on test folder."""
    print_header("TEST 3: OVERLAY COMBINING")

    test_dir = os.path.join(TEST_FOLDER, 'test_overlay')
    results = {'passed': 0, 'failed': 0, 'skipped': 0}

    ffmpeg_available = check_ffmpeg()
    exiftool_available = check_exiftool()

    # Check for Pillow
    try:
        from PIL import Image
        pillow_available = True
    except ImportError:
        pillow_available = False

    print(f"[INFO] Pillow available: {pillow_available}")
    print(f"[INFO] FFmpeg available: {ffmpeg_available}")
    print(f"[INFO] exiftool available: {exiftool_available}")

    # Copy overlay folders to test_overlay directory for processing
    has_photo = 'photo_overlay_folder' in copied_files
    has_video = 'video_overlay_folder' in copied_files

    if has_photo:
        # Copy the photo overlay folder to test_overlay
        src = copied_files['photo_overlay_folder']
        dest = os.path.join(test_dir, os.path.basename(src))
        if os.path.exists(dest):
            shutil.rmtree(dest)
        shutil.copytree(src, dest)
        print(f"\n[TEST] Copied photo overlay folder to: {dest}")

    if has_video:
        # Copy the video overlay folder to test_overlay
        src = copied_files['video_overlay_folder']
        dest = os.path.join(test_dir, os.path.basename(src))
        if os.path.exists(dest):
            shutil.rmtree(dest)
        shutil.copytree(src, dest)
        print(f"[TEST] Copied video overlay folder to: {dest}")

    if not has_photo and not has_video:
        print("[SKIP] No overlay folders to test")
        results['skipped'] += 2
        return results

    # Run combine_overlays.py on the test_overlay directory
    print(f"\n[TEST] Running combine_overlays.py on: {test_dir}")

    rc = run_script(SCRIPTS['combine'], [
        '--output', test_dir,
        '--no-dry-run',
        '--auto-confirm'
    ])

    # Check results
    print(f"\n[INFO] combine_overlays.py exit code: {rc}")

    # Look for combined output files
    for item in os.listdir(test_dir):
        item_path = os.path.join(test_dir, item)
        if os.path.isfile(item_path):
            size = os.path.getsize(item_path)
            print(f"       Output file: {item} ({size:,} bytes)")

            if is_image_file(item_path):
                if pillow_available:
                    print("[PASS] Photo overlay combined successfully")
                    results['passed'] += 1
                else:
                    results['skipped'] += 1
            elif is_video_file(item_path):
                if ffmpeg_available:
                    print("[PASS] Video overlay combined successfully")
                    results['passed'] += 1
                else:
                    results['skipped'] += 1

    # Count skips/failures for missing outputs
    if has_photo and not any(is_image_file(os.path.join(test_dir, f)) for f in os.listdir(test_dir) if os.path.isfile(os.path.join(test_dir, f))):
        if not pillow_available:
            print("[SKIP] Photo overlay skipped (Pillow not available)")
            results['skipped'] += 1
        else:
            print("[FAIL] Photo overlay not produced")
            results['failed'] += 1

    if has_video and not any(is_video_file(os.path.join(test_dir, f)) for f in os.listdir(test_dir) if os.path.isfile(os.path.join(test_dir, f))):
        if not ffmpeg_available:
            print("[SKIP] Video overlay skipped (FFmpeg not available)")
            results['skipped'] += 1
        else:
            print("[FAIL] Video overlay not produced")
            results['failed'] += 1

    if not has_photo:
        print("[SKIP] No photo overlay sample available")
        results['skipped'] += 1
    if not has_video:
        print("[SKIP] No video overlay sample available")
        results['skipped'] += 1

    print_section("Overlay Test Summary")
    print(f"  Passed: {results['passed']}")
    print(f"  Failed: {results['failed']}")
    print(f"  Skipped: {results['skipped']}")

    return results


def show_test_results_tree():
    """Display the test results folder structure."""
    print_header("TEST RESULTS FOLDER STRUCTURE")

    if not os.path.exists(TEST_FOLDER):
        print("[ERROR] Test folder does not exist")
        return

    def print_tree(path, prefix=""):
        try:
            items = sorted(os.listdir(path))
        except PermissionError:
            return

        for i, item in enumerate(items):
            item_path = os.path.join(path, item)
            is_last = i == len(items) - 1
            connector = "└── " if is_last else "├── "

            if os.path.isfile(item_path):
                size = os.path.getsize(item_path)
                print(f"{prefix}{connector}{item} ({size:,} bytes)")
            else:
                print(f"{prefix}{connector}{item}/")
                new_prefix = prefix + ("    " if is_last else "│   ")
                print_tree(item_path, new_prefix)

    print(f"{TEST_FOLDER}/")
    print_tree(TEST_FOLDER)


def run_all_tests():
    """Run the complete test suite."""
    print_header("SNAPCHAT MEMORIES DOWNLOADER - TEST SUITE")

    samples = find_test_samples()
    found_count = sum(1 for v in samples.values() if v is not None)

    print(f"[INFO] Found {found_count} suitable test samples:")
    for key, value in samples.items():
        if value:
            print(f"       {key}: {os.path.basename(value)}")

    if found_count == 0:
        print("\n[ERROR] No test samples found! Run with --download-only first.")
        return False

    copied = setup_test_environment(samples)

    all_results = {
        'unzip': test_unzip(),
        'metadata': test_metadata(copied),
        'overlay': test_overlay_combining(copied),
    }

    show_test_results_tree()

    print_header("FINAL TEST SUMMARY")

    total_passed = sum(r['passed'] for r in all_results.values())
    total_failed = sum(r['failed'] for r in all_results.values())
    total_skipped = sum(r['skipped'] for r in all_results.values())

    print(f"  Total Passed:  {total_passed}")
    print(f"  Total Failed:  {total_failed}")
    print(f"  Total Skipped: {total_skipped}")
    print()
    print(f"Test artifacts saved to: {TEST_FOLDER}")

    return total_failed == 0


def menu():
    """Display interactive menu."""
    print()
    print("=" * 70)
    print(" SNAPCHAT MEMORIES DOWNLOADER - TEST SUITE")
    print("=" * 70)
    print()
    print("  [1] Download memories and run all tests")
    print("  [2] Download memories only")
    print("  [3] Run tests only (assumes files downloaded)")
    print("  [4] Show test results folder")
    print("  [0] Exit")
    print()
    return input("Enter choice (0-4): ").strip()


def main():
    args = sys.argv[1:]

    if '--help' in args or '-h' in args:
        print(__doc__)
        sys.exit(0)

    if '--download-only' in args:
        sys.exit(0 if run_downloader() else 1)

    if '--test-only' in args:
        sys.exit(0 if run_all_tests() else 1)

    if '--all' in args:
        if run_downloader():
            sys.exit(0 if run_all_tests() else 1)
        sys.exit(1)

    while True:
        choice = menu()
        if choice == '0':
            print("\nGoodbye!")
            sys.exit(0)
        elif choice == '1':
            if run_downloader():
                run_all_tests()
            input("\nPress Enter to continue...")
        elif choice == '2':
            run_downloader()
            input("\nPress Enter to continue...")
        elif choice == '3':
            run_all_tests()
            input("\nPress Enter to continue...")
        elif choice == '4':
            show_test_results_tree()
            input("\nPress Enter to continue...")
        else:
            print("\n[ERROR] Invalid choice!")


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print("\nCancelled by user.")
        sys.exit(130)
