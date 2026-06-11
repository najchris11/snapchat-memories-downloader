package com.najdev.snapvault

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSTimeZone
import platform.Foundation.timeZoneWithName

actual val isAndroidBuild: Boolean = false

actual fun nowIsoString(): String {
    val formatter = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        timeZone = NSTimeZone.timeZoneWithName("UTC")!!
    }
    return formatter.stringFromDate(NSDate())
}
