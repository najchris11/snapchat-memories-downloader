#!/usr/bin/env python3
"""
Snapchat Memories Downloader - Orchestrator

This lightweight `run_all.py` only orchestrates the existing scripts:
- snapchat-downloader.py     -> downloads memories
- metadata.py                -> writes GPS/location metadata
- combine_overlays.py        -> merges overlays for images/videos
- delete-dupes.py            -> removes duplicates in extracted folders

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
DEFAULT_OUTPUT = os.path.join(SCRIPT_DIR, 'snapchat_memories')

SCRIPTS = {
    'downloader': os.path.join(SCRIPT_DIR, 'snapchat-downloader.py'),
    'metadata': os.path.join(SCRIPT_DIR, 'metadata.py'),
    'combine': os.path.join(SCRIPT_DIR, 'combine_overlays.py'),
    'dedupes': os.path.join(SCRIPT_DIR, 'delete-dupes.py'),
}

def run_script(path: str, args: list[str] | None = None) -> int:
    """Run a Python script with the current interpreter and return exit code."""
    if not os.path.exists(path):
        print(f"[ERROR] Script not found: {os.path.basename(path)}")
        return 1
    cmd = [sys.executable, path] + (args or [])
    result = subprocess.run(cmd)
    return result.returncode

def parse_args():
    args = sys.argv[1:]
    cfg = {'full': False, 'test': None, 'dry_run': None, 'output': None, 'workers': None}
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
        elif a == '--dry-run':
            cfg['dry_run'] = True
        elif a in ('--apply', '--no-dry-run'):
            cfg['dry_run'] = False
        elif a == '--output' and i + 1 < len(args):
            cfg['output'] = args[i + 1]
            i += 1
        elif a == '--workers' and i + 1 < len(args):
            try:
                cfg['workers'] = max(1, int(args[i + 1]))
            except ValueError:
                pass
            i += 1
        i += 1
    return cfg

def prompt_dry_run(default: bool | None = None) -> bool:
    """Ask the user whether to run in dry-run mode; returns True when preview only."""
    if default is not None:
        return default
    while True:
        choice = input("Run in dry-run mode? (y/n): ").strip().lower()
        if choice in ('y', 'yes', 'j', 'ja'):
            return True
        if choice in ('n', 'no'):
            return False
        print("Please enter 'y' or 'n'.")

def menu() -> str:
    print()
    print("======================================================================")
    print(" SNAPCHAT MEMORIES DOWNLOADER - Orchestrator")
    print("======================================================================")
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
        print(f"[ERROR] '{os.path.basename(HTML_FILE)}' not found!")
        print("   Place your Snapchat 'memories_history.html' in the project root.")
        return 1
    output_dir = cfg.get('output') or DEFAULT_OUTPUT
    os.makedirs(output_dir, exist_ok=True)
    dry_run = prompt_dry_run(cfg.get('dry_run'))
    
    workers_arg = []
    if cfg.get('workers'):
        workers_arg = ['--workers', str(cfg['workers'])]
    
    print("\n== Step 1/4: Download Memories ==")
    dl_args = ['--output', output_dir] + workers_arg
    if cfg.get('test') is not None:
        if cfg['test'] > 0:
            dl_args.extend(['--test', str(cfg['test'])])
        else:
            dl_args.append('--test')
    rc = run_script(SCRIPTS['downloader'], dl_args)
    if rc != 0:
        return rc
    print("\n== Step 2/4: Add GPS Metadata ==")
    rc = run_script(SCRIPTS['metadata'], ['--output', output_dir] + workers_arg)
    if rc != 0:
        return rc
    print("\n== Step 3/4: Combine Overlays ==")
    combine_args = (['--dry-run'] if dry_run else ['--no-dry-run']) + ['--output', output_dir] + workers_arg
    rc = run_script(SCRIPTS['combine'], combine_args)
    if rc != 0:
        return rc
    print("\n== Step 4/4: Delete Duplicates ==")
    dedupe_args = (['--dry-run'] if dry_run else ['--no-dry-run']) + ['--output', output_dir] + workers_arg
    rc = run_script(SCRIPTS['dedupes'], dedupe_args)
    return rc

def main():
    cfg = parse_args()
    if cfg['full']:
        rc = run_all_steps(cfg)
        sys.exit(rc)

    choice = menu()
    if choice == '0':
        print("\nGoodbye!")
        sys.exit(0)
    elif choice == '1':
        sys.exit(run_all_steps(cfg))
    elif choice == '2':
        dl_args = []
        if cfg.get('test') is not None:
            dl_args = ['--test', str(cfg['test'])] if cfg['test'] > 0 else ['--test']
        output_dir = cfg.get('output') or DEFAULT_OUTPUT
        os.makedirs(output_dir, exist_ok=True)
        workers_arg = ['--workers', str(cfg['workers'])] if cfg.get('workers') else []
        sys.exit(run_script(SCRIPTS['downloader'], dl_args + ['--output', output_dir] + workers_arg))
    elif choice == '3':
        output_dir = cfg.get('output') or DEFAULT_OUTPUT
        os.makedirs(output_dir, exist_ok=True)
        workers_arg = ['--workers', str(cfg['workers'])] if cfg.get('workers') else []
        sys.exit(run_script(SCRIPTS['metadata'], ['--output', output_dir] + workers_arg))
    elif choice == '4':
        dry_run = prompt_dry_run(cfg.get('dry_run'))
        output_dir = cfg.get('output') or DEFAULT_OUTPUT
        os.makedirs(output_dir, exist_ok=True)
        workers_arg = ['--workers', str(cfg['workers'])] if cfg.get('workers') else []
        args = (['--dry-run'] if dry_run else ['--no-dry-run']) + ['--output', output_dir] + workers_arg
        sys.exit(run_script(SCRIPTS['combine'], args))
    elif choice == '5':
        dry_run = prompt_dry_run(cfg.get('dry_run'))
        output_dir = cfg.get('output') or DEFAULT_OUTPUT
        os.makedirs(output_dir, exist_ok=True)
        workers_arg = ['--workers', str(cfg['workers'])] if cfg.get('workers') else []
        args = (['--dry-run'] if dry_run else ['--no-dry-run']) + ['--output', output_dir] + workers_arg
        sys.exit(run_script(SCRIPTS['dedupes'], args))
    else:
        print("\n[ERROR] Invalid choice!")
        sys.exit(1)

if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print("\nCancelled by user.")
        sys.exit(130)

