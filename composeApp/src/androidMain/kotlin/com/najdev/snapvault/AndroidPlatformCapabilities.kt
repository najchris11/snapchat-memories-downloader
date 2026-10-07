package com.najdev.snapvault

// AndroidMediaProcessor writes photo EXIF through ExifInterface and composites photo
// overlays with Canvas. AndroidZipPipelineRunner has no own plain-photo date pass (#51),
// while Android ZIP reading remains stubbed (#52). It skips video overlay pairs.
actual val platformMediaCapabilities = androidMediaCapabilities
