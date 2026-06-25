#!/usr/bin/env python3
import sys
import os
import json
import subprocess
import threading
import time
import signal

# Global variable to track the current child process
current_process = None

# Helper to print JSON messages
def log(message, type="info", progress=None):
    data = {"type": type, "message": message}
    if progress is not None:
        data["progress"] = progress
    print(json.dumps(data), flush=True)

def handle_signal(signum, frame):
    """Handle kill signals by terminating the child process group."""
    global current_process
    if current_process:
        log(f"Received signal {signum}, stopping subprocess group...", "info")
        try:
            # Kill the entire process group
            os.killpg(os.getpgid(current_process.pid), signal.SIGTERM)
            try:
                current_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(os.getpgid(current_process.pid), signal.SIGKILL)
        except Exception as e:
            log(f"Error stopping process: {e}", "error")
    sys.exit(0)

# Register signal handlers
signal.signal(signal.SIGINT, handle_signal)
signal.signal(signal.SIGTERM, handle_signal)

def run_script(script_name, args):
    """Runs a script and captures its output to stream back as JSON logs."""
    global current_process
    script_path = os.path.join(os.path.dirname(__file__), script_name)
    
    if not os.path.exists(script_path):
        log(f"Script not found: {script_name}", "error")
        return False
        
    cmd = [sys.executable, script_path] + args
    log(f"Starting {script_name}...", "info")
    
    try:
        # We use Popen with start_new_session=True to create a new process group
        # This allows us to kill the process and all its children/threads
        current_process = subprocess.Popen(
            cmd, 
            stdout=subprocess.PIPE, 
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            start_new_session=True
        )
        
        for line in current_process.stdout:
            line = line.strip()
            if line:
                log(line, "log")
                
        current_process.wait()
        return_code = current_process.returncode
        current_process = None
        
        if return_code == 0:
            log(f"Finished {script_name}", "success")
            return True
        else:
            log(f"{script_name} failed with code {return_code}", "error")
            return False
            
    except Exception as e:
        log(f"Error running {script_name}: {str(e)}", "error")
        current_process = None
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
