package com.najdev.snapvault

// AndroidMediaProcessor writes photo EXIF through ExifInterface and composites photo
// overlays with Canvas. It has no video metadata writer, while AndroidZipPipelineRunner
// deliberately reports video pairs as OverlayCombineStatus.SkippedVideo.
actual val platformMediaCapabilities = MediaCapabilities(
    imageMetadata = CapabilityLevel.Full,
    videoMetadata = CapabilityLevel.Unavailable,
    imageOverlayCombine = CapabilityLevel.Full,
    videoOverlayCombine = CapabilityLevel.Unavailable,
)
