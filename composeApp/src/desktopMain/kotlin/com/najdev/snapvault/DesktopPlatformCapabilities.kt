package com.najdev.snapvault

// Desktop writes image and video metadata with ExifTool, composites images with ImageIO,
// and composites video overlays with FFmpeg. Settings separately reports whether the two
// required binaries are present on this machine.
actual val platformMediaCapabilities = desktopMediaCapabilities
