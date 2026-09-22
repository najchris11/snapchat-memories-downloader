package com.najdev.snapvault

// IosMediaProcessor writes embedded photo metadata with ImageIO. Videos receive only a
// filesystem modification date, not embedded date/GPS metadata. IosZipPipelineRunner's
// combineAll is currently a stub, so neither overlay type is combined on this branch.
// imageOverlayCombine moves to Full when the concurrent iOS combining work lands.
actual val platformMediaCapabilities = MediaCapabilities(
    imageMetadata = CapabilityLevel.Full,
    videoMetadata = CapabilityLevel.Partial,
    imageOverlayCombine = CapabilityLevel.Unavailable,
    videoOverlayCombine = CapabilityLevel.Unavailable,
)
