@file:JvmName("MediaScannerCommon")
package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import com.najdev.snapvault.ui.LibraryItem
import okio.FileSystem
import okio.Path.Companion.toPath
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
    // Keyed by the file's size and modification time as well as its path. Keyed by path alone,
    // a file replaced under the same name kept its old thumbnail for the rest of the session,
    // however correct the disk cache underneath had become (D19).
    val meta = FileSystem.SYSTEM.metadataOrNull(path.toPath())
    val key = "$path|${meta?.size}|${meta?.lastModifiedAtMillis}"
    ThumbnailCache.get(key)?.let { return it }
    val bitmap = loadThumbnail(path)
    if (bitmap != null) {
        ThumbnailCache.put(key, bitmap)
    }
    return bitmap
}
