#!/usr/bin/env python3
"""
Snapchat Memories Downloader - Orchestrator

This lightweight `run_all.py` only orchestrates the existing scripts:
- snapchat-downloader.py     → downloads memories
- metadata.py                → writes GPS/location metadata
- combine_overlays.py        → merges overlays for images/videos
- delete-dupes.py            → removes duplicates in extracted folders

Usage:
    python3 run_all.py              # Interactive menu
    python3 run_all.py --full       # Run all steps sequentially
    python3 run_all.py --help       # Show help
"""

import os
import sys
import subprocess

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
HTML_FILE = os.path.join(SCRIPT_DIR, 'memories_history.html')

SCRIPTS = {
    'downloader': os.path.join(SCRIPT_DIR, 'snapchat-downloader.py'),
    'metadata': os.path.join(SCRIPT_DIR, 'metadata.py'),
    'combine': os.path.join(SCRIPT_DIR, 'combine_overlays.py'),
    'dedupes': os.path.join(SCRIPT_DIR, 'delete-dupes.py'),
}

def run_script(path: str, args: list[str] | None = None) -> int:
    """Run a Python script with the current interpreter and return exit code."""
    if not os.path.exists(path):
        print(f"❌ Script not found: {os.path.basename(path)}")
        return 1
    cmd = [sys.executable, path] + (args or [])
    result = subprocess.run(cmd)
    return result.returncode

def parse_args():
    args = sys.argv[1:]
    cfg = {'full': False, 'test': None}
    if '--help' in args or '-h' in args:
        print(__doc__)
        sys.exit(0)
    i = 0
    while i < len(args):
        a = args[i]
        if a == '--full':
            cfg['full'] = True
        elif a == '--test':
            # Optional numeric limit
            if i + 1 < len(args) and args[i + 1].isdigit():
                cfg['test'] = int(args[i + 1])
                i += 1
            else:
                cfg['test'] = 0  # flag present without limit
        i += 1
    return cfg

def menu() -> str:
    print()
    print("╔══════════════════════════════════════════════════════════════════════╗")
    print("║          SNAPCHAT MEMORIES DOWNLOADER - Orchestrator                 ║")
    print("╚══════════════════════════════════════════════════════════════════════╝")
    print()
    print("What would you like to do?")
    print()
    print("  [1] Run ALL steps (recommended)")
    print("  [2] Download memories only")
    print("  [3] Add GPS metadata only")
    print("  [4] Combine overlays only")
    print("  [5] Delete duplicates only")
    print("  [0] Exit")
    print()
    return input("Enter choice (0-5): ").strip()

def run_all_steps(cfg: dict) -> int:
    if not os.path.exists(HTML_FILE):
        print(f"❌ '{os.path.basename(HTML_FILE)}' not found!")
        print("   Place your Snapchat 'memories_history.html' in the project root.")
        return 1
    print("\n== Step 1/4: Download Memories ==")
    dl_args = []
    if cfg.get('test') is not None:
        if cfg['test'] > 0:
            dl_args = ['--test', str(cfg['test'])]
        else:
            dl_args = ['--test']
    rc = run_script(SCRIPTS['downloader'], dl_args)
    if rc != 0:
        return rc
    print("\n== Step 2/4: Add GPS Metadata ==")
    rc = run_script(SCRIPTS['metadata'])
    if rc != 0:
        return rc
    print("\n== Step 3/4: Combine Overlays ==")
    rc = run_script(SCRIPTS['combine'])
    if rc != 0:
        return rc
    print("\n== Step 4/4: Delete Duplicates ==")
    rc = run_script(SCRIPTS['dedupes'])
    return rc

def main():
    cfg = parse_args()
    if cfg['full']:
        rc = run_all_steps(cfg)
        sys.exit(rc)

    choice = menu()
    if choice == '0':
        print("\nGoodbye! 👋")
        sys.exit(0)
    elif choice == '1':
        sys.exit(run_all_steps(cfg))
    elif choice == '2':
        dl_args = []
        if cfg.get('test') is not None:
            dl_args = ['--test', str(cfg['test'])] if cfg['test'] > 0 else ['--test']
        sys.exit(run_script(SCRIPTS['downloader'], dl_args))
    elif choice == '3':
        sys.exit(run_script(SCRIPTS['metadata']))
    elif choice == '4':
        sys.exit(run_script(SCRIPTS['combine']))
    elif choice == '5':
        sys.exit(run_script(SCRIPTS['dedupes']))
    else:
        print("\n❌ Invalid choice!")
        sys.exit(1)

if __name__ == '__main__':
    main()

