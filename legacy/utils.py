#!/usr/bin/env python3
"""
Shared utility functions for Snapchat Memories Downloader scripts.
"""

import os
import re
import subprocess
import hashlib
from datetime import datetime


def check_exiftool():
    """Check whether exiftool is installed."""
    try:
        subprocess.run(['exiftool', '-ver'], capture_output=True, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False


def check_ffmpeg():
    """Check if ffmpeg is installed."""
    try:
        subprocess.run(['ffmpeg', '-version'], capture_output=True, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False


def extract_unique_id_from_url(url):
    """Extract the unique ID (mid) from the URL."""
    mid_match = re.search(r'mid=([a-zA-Z0-9\-]+)', url)
    if mid_match:
        return mid_match.group(1)
    else:
        # Fallback: Hash the entire URL for ID purposes
        return hashlib.md5(url.encode()).hexdigest()


def parse_date_string(date_str):
    """Parse a date string into a datetime object."""
    if not date_str:
        return None
    
    try:
        date_cleaned = date_str.strip()
        for fmt in ['%Y-%m-%d %H:%M:%S %Z', '%Y-%m-%d %H:%M:%S', '%Y-%m-%d', 
                    '%d.%m.%Y %H:%M:%S', '%d.%m.%Y']:
            try:
                dt = datetime.strptime(date_cleaned.replace('UTC', '').strip(), fmt.replace(' %Z', ''))
                return dt
            except ValueError:
                continue
    except Exception:
        pass
    return None


def format_exif_datetime(dt):
    """Format a datetime object into EXIF datetime string ``'%Y:%m:%d %H:%M:%S'``.

    Returns ``None`` if ``dt`` is ``None`` or otherwise falsy.
    """
    return dt.strftime('%Y:%m:%d %H:%M:%S') if dt else None
