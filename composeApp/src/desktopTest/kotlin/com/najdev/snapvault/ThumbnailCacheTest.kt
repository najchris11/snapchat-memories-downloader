package com.najdev.snapvault

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

// ThumbnailCache is an LRU hand-rolled on LinkedHashMap insertion order, because the
// JVM's access-order constructor is not reachable from common code. Until this round the
// cache had exactly one caller — the VideoPlayer that was never invoked — so none of this
// behaviour had ever run in the app, let alone been tested. The Library grid now depends
// on it for every visible tile.
class ThumbnailCacheTest {

    private fun bitmap() = ImageBitmap(1, 1)

    @BeforeTest
    fun setUp() = ThumbnailCache.clear()

    @AfterTest
    fun tearDown() = ThumbnailCache.clear()

    @Test
    fun storesAndReturnsTheSameInstance() {
        val bmp = bitmap()
        ThumbnailCache.put("/a.jpg", bmp)

        assertSame(bmp, ThumbnailCache.get("/a.jpg"))
    }

    @Test
    fun missReturnsNull() {
        assertNull(ThumbnailCache.get("/never-added.jpg"))
    }

    @Test
    fun putForAnExistingKeyReplacesRatherThanDuplicating() {
        val first = bitmap()
        val second = bitmap()
        ThumbnailCache.put("/a.jpg", first)
        ThumbnailCache.put("/a.jpg", second)

        assertSame(second, ThumbnailCache.get("/a.jpg"))
    }

    @Test
    fun clearEmptiesTheCache() {
        ThumbnailCache.put("/a.jpg", bitmap())
        ThumbnailCache.clear()

        assertNull(ThumbnailCache.get("/a.jpg"))
    }

    // The cache is bounded at 150 entries. Scrolling a 10,000-item library must not grow it
    // without limit, and the oldest entry is the one that goes.
    @Test
    fun evictsTheLeastRecentlyUsedEntryOnceFull() {
        repeat(MAX + 1) { i -> ThumbnailCache.put("/img-$i.jpg", bitmap()) }

        assertNull(ThumbnailCache.get("/img-0.jpg"), "oldest entry should have been evicted")
        assertNotNull(ThumbnailCache.get("/img-1.jpg"), "second-oldest should still be present")
        assertNotNull(ThumbnailCache.get("/img-$MAX.jpg"), "newest should be present")
    }

    // The LRU part specifically: a hit has to move the entry to newest, otherwise the cache
    // degrades into insertion-order FIFO and evicts tiles the user is actively looking at.
    @Test
    fun readingAnEntryProtectsItFromTheNextEviction() {
        repeat(MAX) { i -> ThumbnailCache.put("/img-$i.jpg", bitmap()) }

        // Touch the oldest entry, making it the newest.
        assertNotNull(ThumbnailCache.get("/img-0.jpg"))

        // Now overflow by one. Under FIFO this would drop img-0; under LRU it drops img-1.
        ThumbnailCache.put("/img-new.jpg", bitmap())

        assertNotNull(ThumbnailCache.get("/img-0.jpg"), "recently read entry must survive")
        assertNull(ThumbnailCache.get("/img-1.jpg"), "next-oldest should have been evicted instead")
    }

    private companion object {
        // Mirrors ThumbnailCache.MAX_SIZE, which is private.
        const val MAX = 150
    }
}
