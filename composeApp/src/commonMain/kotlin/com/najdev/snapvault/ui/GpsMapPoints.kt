package com.najdev.snapvault.ui

internal data class MapMemory(val item: LibraryItem, val x: Float, val y: Float)

/** Plate carrée projection; entries without trustworthy coordinates stay off the map. */
internal fun libraryMapPoints(items: List<LibraryItem>): List<MapMemory> = items.mapNotNull { item ->
    if (!item.hasGps) return@mapNotNull null
    val lat = item.latitude ?: return@mapNotNull null
    val lon = item.longitude ?: return@mapNotNull null
    if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
    MapMemory(item, ((lon + 180.0) / 360.0).toFloat(), ((90.0 - lat) / 180.0).toFloat())
}
