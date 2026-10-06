package com.najdev.snapvault

enum class CapabilityLevel { Full, Partial, Unavailable }

data class MediaCapabilities(
    val imageMetadata: CapabilityLevel,
    val videoMetadata: CapabilityLevel,
    val imageOverlayCombine: CapabilityLevel,
    val videoOverlayCombine: CapabilityLevel,
)

expect val platformMediaCapabilities: MediaCapabilities

internal val androidMediaCapabilities = MediaCapabilities(
    // #51: ZIP import writes GPS when present but skips the date pass for plain photos.
    imageMetadata = CapabilityLevel.Partial,
    // AndroidMediaProcessor has no video metadata writer.
    videoMetadata = CapabilityLevel.Unavailable,
    // AndroidMediaProcessor composites photo overlays with Canvas.
    imageOverlayCombine = CapabilityLevel.Full,
    // AndroidZipPipelineRunner deliberately skips video overlay pairs.
    videoOverlayCombine = CapabilityLevel.Unavailable,
)

internal val iosMediaCapabilities = MediaCapabilities(
    // IosMediaProcessor writes photo date and location metadata.
    imageMetadata = CapabilityLevel.Full,
    // Videos receive filesystem modification dates only.
    videoMetadata = CapabilityLevel.Partial,
    // IosZipPipelineRunner combines photo overlays through IosMediaProcessor (#47).
    imageOverlayCombine = CapabilityLevel.Full,
    // IosMediaProcessor.combineVideoWithOverlay returns false.
    videoOverlayCombine = CapabilityLevel.Unavailable,
)

internal val desktopMediaCapabilities = MediaCapabilities(
    // ExifTool writes photo and video metadata.
    imageMetadata = CapabilityLevel.Full,
    videoMetadata = CapabilityLevel.Full,
    // ImageIO combines photo overlays; FFmpeg combines video overlays.
    imageOverlayCombine = CapabilityLevel.Full,
    videoOverlayCombine = CapabilityLevel.Full,
)

enum class MediaCapability {
    ImageMetadata,
    VideoMetadata,
    ImageOverlayCombine,
    VideoOverlayCombine,
}

data class CapabilityStatus(
    val capability: MediaCapability,
    val level: CapabilityLevel,
)

data class CapabilityBannerState(val capabilities: List<CapabilityStatus>) {
    val limitations: List<CapabilityStatus>
        get() = capabilities.filter { it.level != CapabilityLevel.Full }
}

fun capabilityBannerState(capabilities: MediaCapabilities): CapabilityBannerState? {
    val levels = listOf(
        CapabilityStatus(MediaCapability.ImageMetadata, capabilities.imageMetadata),
        CapabilityStatus(MediaCapability.VideoMetadata, capabilities.videoMetadata),
        CapabilityStatus(MediaCapability.ImageOverlayCombine, capabilities.imageOverlayCombine),
        CapabilityStatus(MediaCapability.VideoOverlayCombine, capabilities.videoOverlayCombine),
    )

    return levels.takeIf { entries -> entries.any { it.level != CapabilityLevel.Full } }
        ?.let(::CapabilityBannerState)
}
