#!/usr/bin/env python3
import sys
import os
import json
import subprocess
import threading
import time

# Helper to print JSON messages
def log(message, type="info", progress=None):
    data = {"type": type, "message": message}
    if progress is not None:
        data["progress"] = progress
    print(json.dumps(data), flush=True)

def run_script(script_name, args):
    """Runs a script and captures its output to stream back as JSON logs."""
    script_path = os.path.join(os.path.dirname(__file__), script_name)
    
    if not os.path.exists(script_path):
        log(f"Script not found: {script_name}", "error")
        return False
        
    cmd = [sys.executable, script_path] + args
    log(f"Starting {script_name}...", "info")
    
    try:
        # We use Popen to read stdout in real-time
        process = subprocess.Popen(
            cmd, 
            stdout=subprocess.PIPE, 
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1
        )
        
        for line in process.stdout:
            line = line.strip()
            if line:
                log(line, "log")
                
        process.wait()
        
        if process.returncode == 0:
            log(f"Finished {script_name}", "success")
            return True
        else:
            log(f"{script_name} failed with code {process.returncode}", "error")
            return False
            
    except Exception as e:
        log(f"Error running {script_name}: {str(e)}", "error")
        return False

def main():
    if len(sys.argv) < 2:
        log("Usage: api.py <command> [args...]", "error")
        return

    command = sys.argv[1]
    
    # Extract --output argument if present
    output_folder = None
    args_list = list(sys.argv[2:])
    if '--output' in args_list:
        idx = args_list.index('--output')
        if idx + 1 < len(args_list):
            output_folder = args_list[idx + 1]
            # Remove --output and its value from args
            args_list.pop(idx)
            args_list.pop(idx)
    
    if command == "download":
        # Usage: api.py download <html_file> [--output folder] [workers] [test_mode]
        if not args_list:
            log("Missing HTML file argument", "error")
            return
        html_file = args_list[0]
        script_args = [html_file]
        
        # Add output folder if specified
        if output_folder:
            script_args.extend(['--output', output_folder])
        
        # Parse remaining optional args
        if len(args_list) > 1:
            script_args.extend(args_list[1:])
            
        run_script("snapchat-downloader.py", script_args)
        
    elif command == "metadata":
        if not args_list:
            log("Missing HTML file argument", "error")
            return
        html_file = args_list[0]
        script_args = [html_file]
        if output_folder:
            script_args.extend(['--output', output_folder])
        run_script("metadata.py", script_args)
        
    elif command == "combine":
        script_args = []
        if output_folder:
            script_args.extend(['--output', output_folder])
        run_script("combine_overlays.py", script_args)
        
    elif command == "dedupe":
        script_args = []
        if output_folder:
            script_args.extend(['--output', output_folder])
        run_script("delete-dupes.py", script_args)
        
    else:
        log(f"Unknown command: {command}", "error")

if __name__ == "__main__":
    main()
