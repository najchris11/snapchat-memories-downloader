package com.najdev.snapvault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformCapabilitiesTest {

    // ZIP imports can combine supported videos. Their originals retain the verified metadata.
    @Test
    fun androidCapabilitiesMatchPipeline() {
        assertEquals(
            MediaCapabilities(
                imageMetadata = CapabilityLevel.Partial,
                videoMetadata = CapabilityLevel.Unavailable,
                imageOverlayCombine = CapabilityLevel.Full,
                videoOverlayCombine = CapabilityLevel.Partial,
            ),
            androidMediaCapabilities,
        )
    }

    // Regression: iOS photo overlay combining was still reported unavailable after it
    // shipped in the ZIP pipeline (#47).
    @Test
    fun iosCapabilitiesIncludePhotoOverlayCombining() {
        assertEquals(
            MediaCapabilities(
                imageMetadata = CapabilityLevel.Full,
                videoMetadata = CapabilityLevel.Partial,
                imageOverlayCombine = CapabilityLevel.Full,
                videoOverlayCombine = CapabilityLevel.Partial,
            ),
            iosMediaCapabilities,
        )
    }

    @Test
    fun desktopCapabilitiesIncludeAllFourMediaFeatures() {
        assertEquals(
            MediaCapabilities(
                imageMetadata = CapabilityLevel.Full,
                videoMetadata = CapabilityLevel.Full,
                imageOverlayCombine = CapabilityLevel.Full,
                videoOverlayCombine = CapabilityLevel.Full,
            ),
            desktopMediaCapabilities,
        )
    }

    // Regression: the dashboard used an Android-only flag, so iOS's real media-processing
    // gaps produced no warning at all. Banner state must come from every non-full capability.
    @Test
    fun bannerStateContainsEveryNonFullCapability() {
        val state = capabilityBannerState(
            MediaCapabilities(
                imageMetadata = CapabilityLevel.Full,
                videoMetadata = CapabilityLevel.Partial,
                imageOverlayCombine = CapabilityLevel.Unavailable,
                videoOverlayCombine = CapabilityLevel.Unavailable,
            )
        )

        assertEquals(
            listOf(
                CapabilityStatus(MediaCapability.VideoMetadata, CapabilityLevel.Partial),
                CapabilityStatus(MediaCapability.ImageOverlayCombine, CapabilityLevel.Unavailable),
                CapabilityStatus(MediaCapability.VideoOverlayCombine, CapabilityLevel.Unavailable),
            ),
            state?.limitations,
        )
    }

    @Test
    fun bannerStateIsAbsentWhenEverythingIsFull() {
        assertNull(
            capabilityBannerState(
                MediaCapabilities(
                    imageMetadata = CapabilityLevel.Full,
                    videoMetadata = CapabilityLevel.Full,
                    imageOverlayCombine = CapabilityLevel.Full,
                    videoOverlayCombine = CapabilityLevel.Full,
                )
            )
        )
    }
}
