@file:JvmName("MediaScannerCommon")
package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import com.najdev.snapvault.ui.LibraryItem

expect fun scanMediaFiles(folderPath: String): List<LibraryItem>
expect fun loadThumbnail(path: String): ImageBitmap?
expect fun loadFullImage(path: String): ImageBitmap?

object ThumbnailCache {
    private const val MAX_SIZE = 150
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MAX_SIZE
        }
    }

    fun get(path: String): ImageBitmap? = synchronized(cache) {
        cache[path]
    }

    fun put(path: String, bitmap: ImageBitmap) = synchronized(cache) {
        cache[path] = bitmap
    }

    fun clear() = synchronized(cache) {
        cache.clear()
    }
}

fun getCachedThumbnail(path: String): ImageBitmap? {
    ThumbnailCache.get(path)?.let { return it }
    val bitmap = loadThumbnail(path)
    if (bitmap != null) {
        ThumbnailCache.put(path, bitmap)
    }
    return bitmap
}
