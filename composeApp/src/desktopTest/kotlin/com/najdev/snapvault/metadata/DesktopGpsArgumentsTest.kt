package com.najdev.snapvault.metadata

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopGpsArgumentsTest {
    @Test
    fun westernVideoLongitudeStaysSignedForXmp() {
        // ExifTool writes MP4 GPSLongitude as XMP. It ignores GPSLongitudeRef=W there;
        // passing the absolute value used to turn western locations into eastern ones.
        assertEquals(
            listOf("-GPSLatitude=40.0", "-GPSLongitude=-83.0"),
            gpsCoordinateArguments("mp4", 40.0, -83.0),
        )
    }

    @Test
    fun imageGpsKeepsExifHemisphereReferences() {
        assertEquals(
            listOf("-GPSLatitude=40.0", "-GPSLatitudeRef=S", "-GPSLongitude=83.0", "-GPSLongitudeRef=W"),
            gpsCoordinateArguments("jpg", -40.0, -83.0),
        )
    }
}
