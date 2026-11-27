package com.najdev.snapvault

import com.najdev.snapvault.ui.LibraryItem
import com.najdev.snapvault.ui.libraryMapPoints
import kotlin.test.Test
import kotlin.test.assertEquals

class GpsMapTest {
    @Test
    fun onlyKnownValidCoordinatesArePlacedOnTheOfflineMap() {
        fun item(id: String, lat: Double?, lon: Double?, tagged: Boolean = true) = LibraryItem(
            id, "Jan 01, 2024", id, "photo", tagged, false, latitude = lat, longitude = lon,
        )
        val points = libraryMapPoints(listOf(
            item("greenwich", 0.0, 0.0), item("northEast", 90.0, 180.0),
            item("legacy index", null, null), item("invalid", 91.0, 0.0),
            item("nan", Double.NaN, 1.0), item("untagged", 1.0, 1.0, false),
        ))
        assertEquals(listOf("greenwich", "northEast"), points.map { it.item.id })
        assertEquals(0.5f, points[0].x)
        assertEquals(0.5f, points[0].y)
        assertEquals(1f, points[1].x)
        assertEquals(0f, points[1].y)
    }
}
