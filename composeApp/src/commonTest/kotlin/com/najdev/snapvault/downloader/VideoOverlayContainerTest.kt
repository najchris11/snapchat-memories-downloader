package com.najdev.snapvault.downloader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoOverlayContainerTest {
    @Test fun onlyContainersMatchingTheNativeEncoderAreAttempted() {
        for (path in listOf("clip.mp4", "clip.MOV", "clip.m4v")) assertTrue(supportsNativeVideoOverlayContainer(path))
        for (path in listOf("clip.avi", "clip.mkv", "clip", "clip.mp4.tmp")) assertFalse(supportsNativeVideoOverlayContainer(path))
    }
}
