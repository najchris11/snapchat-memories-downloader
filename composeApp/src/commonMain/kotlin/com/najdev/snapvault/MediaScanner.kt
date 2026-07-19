@file:JvmName("MediaScannerCommon")
package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import com.najdev.snapvault.ui.LibraryItem
import kotlin.jvm.JvmName

expect fun scanMediaFiles(folderPath: String): List<LibraryItem>
expect fun loadThumbnail(path: String): ImageBitmap?
expect fun loadFullImage(path: String): ImageBitmap?

// LRU via insertion order: a hit is removed and reinserted so it becomes newest;
// eviction drops the first (= least recently used) key. JVM's access-order
// LinkedHashMap constructor isn't available in common code.
object ThumbnailCache {
    private const val MAX_SIZE = 150
    private val lock = SyncLock()
    private val cache = LinkedHashMap<String, ImageBitmap>()

    fun get(path: String): ImageBitmap? = lock.withLock {
        val hit = cache.remove(path) ?: return@withLock null
        cache[path] = hit
        hit
    }

    fun put(path: String, bitmap: ImageBitmap): Unit = lock.withLock {
        cache.remove(path)
        cache[path] = bitmap
        if (cache.size > MAX_SIZE) cache.remove(cache.keys.first())
    }

    fun clear(): Unit = lock.withLock { cache.clear() }
}

fun getCachedThumbnail(path: String): ImageBitmap? {
    ThumbnailCache.get(path)?.let { return it }
    val bitmap = loadThumbnail(path)
    if (bitmap != null) {
        ThumbnailCache.put(path, bitmap)
    }
    return bitmap
}
