package com.najdev.snapvault.metadata

// Every image/video format the desktop pipeline actually reads or writes tags for, in one
// place. Before this (BUG-18), MediaScanner's Library filter, DesktopMediaProcessor's
// exiftool tag-argument selection, and OverlayCombiner's video/ImageIO-fallback detection
// each hand-maintained their own copy of this list — a file in a format the pipeline itself
// produced (e.g. a -main.heic or -main.mkv with no overlay pair) could end up invisible in
// the Library because its extension wasn't in MediaScanner's separate, narrower set.
object SupportedMediaExtensions {
    val IMAGE = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "tiff", "tif")
    val VIDEO = setOf("mp4", "mov", "avi", "mkv", "m4v")
    val ALL = IMAGE + VIDEO
}
