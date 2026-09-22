package com.najdev.snapvault

enum class CapabilityLevel { Full, Partial, Unavailable }

data class MediaCapabilities(
    val imageMetadata: CapabilityLevel,
    val videoMetadata: CapabilityLevel,
    val imageOverlayCombine: CapabilityLevel,
    val videoOverlayCombine: CapabilityLevel,
)

expect val platformMediaCapabilities: MediaCapabilities

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
