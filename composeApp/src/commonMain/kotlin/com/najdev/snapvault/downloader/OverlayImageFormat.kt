package com.najdev.snapvault.downloader

enum class OverlayImageFormat { Png, Jpeg }

fun overlayImageFormatForOutput(outputPath: String): OverlayImageFormat =
    if (outputPath.substringAfterLast('.', "").lowercase() == "png") OverlayImageFormat.Png
    else OverlayImageFormat.Jpeg
