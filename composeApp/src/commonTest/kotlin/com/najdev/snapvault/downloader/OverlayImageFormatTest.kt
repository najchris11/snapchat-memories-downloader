package com.najdev.snapvault.downloader

import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayImageFormatTest {

    // Regression (#50): Android always encoded JPEG, even when the output retained its
    // .png name, so the file extension described different bytes from those on disk.
    @Test
    fun pngOutputUsesPngEncoder() {
        assertEquals(OverlayImageFormat.Png, overlayImageFormatForOutput("memory.png"))
        assertEquals(OverlayImageFormat.Png, overlayImageFormatForOutput("memory.PNG"))
    }

    @Test
    fun nonPngOutputUsesJpegEncoder() {
        assertEquals(OverlayImageFormat.Jpeg, overlayImageFormatForOutput("memory.jpg"))
        assertEquals(OverlayImageFormat.Jpeg, overlayImageFormatForOutput("memory.jpeg"))
        assertEquals(OverlayImageFormat.Jpeg, overlayImageFormatForOutput("memory.webp"))
    }
}
