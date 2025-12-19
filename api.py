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
    
    if command == "download":
        # Usage: api.py download <html_file> [workers] [test_mode]
        html_file = sys.argv[2]
        args = [html_file]
        
        # Parse optional args
        # We expect JSON stringified options or just positional args
        # Simplified: just passthrough for now
        if len(sys.argv) > 3:
            args.extend(sys.argv[3:])
            
        run_script("snapchat-downloader.py", args)
        
    elif command == "metadata":
        html_file = sys.argv[2]
        args = [html_file]
        run_script("metadata.py", args)
        
    elif command == "combine":
        run_script("combine_overlays.py", [])
        
    elif command == "dedupe":
        run_script("delete-dupes.py", [])
        
    else:
        log(f"Unknown command: {command}", "error")

if __name__ == "__main__":
    main()
