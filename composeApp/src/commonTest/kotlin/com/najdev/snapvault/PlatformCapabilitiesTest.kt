package com.najdev.snapvault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformCapabilitiesTest {

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
