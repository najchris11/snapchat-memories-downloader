package com.najdev.snapvault

// AndroidMediaProcessor writes photo EXIF through ExifInterface and composites photo
// overlays with Canvas. AndroidZipPipelineRunner misses plain-photo dates (#51), has no
// video metadata pass, and deliberately skips video overlay pairs.
actual val platformMediaCapabilities = androidMediaCapabilities
