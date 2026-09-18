package com.fakelocation.core.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoUtilsTest {

    @Test
    fun `bearing north`() {
        // from (0,0) to (1,0) is due north, bearing = 0°
        val b = GeoUtils.bearing(0.0, 0.0, 1.0, 0.0)
        assertEquals(0.0, b, 0.001)
    }

    @Test
    fun `bearing east`() {
        // from (0,0) to (0,1) is due east, bearing = 90°
        val b = GeoUtils.bearing(0.0, 0.0, 0.0, 1.0)
        assertEquals(90.0, b, 0.001)
    }

    @Test
    fun `bearing south`() {
        val b = GeoUtils.bearing(1.0, 0.0, 0.0, 0.0)
        assertEquals(180.0, b, 0.001)
    }

    @Test
    fun `bearing west`() {
        val b = GeoUtils.bearing(0.0, 1.0, 0.0, 0.0)
        assertEquals(270.0, b, 0.001)
    }

    @Test
    fun `haversine short distance`() {
        // 1 degree latitude ≈ 111.2 km
        val d = GeoUtils.haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, d, 200.0) // ±200m tolerance
    }

    @Test
    fun `kmh to mps conversion`() {
        assertEquals(1.0, GeoUtils.kmhToMps(3.6), 0.001)
    }

    @Test
    fun `bearing normalized to 0_360`() {
        assertEquals(45.0, GeoUtils.normalizeBearing(45.0), 0.001)
        assertEquals(45.0, GeoUtils.normalizeBearing(405.0), 0.001)
        assertEquals(315.0, GeoUtils.normalizeBearing(-45.0), 0.001)
    }

    @Test
    fun `angle diff is in minus 180 180`() {
        assertTrue(GeoUtils.angleDiff(350.0, 10.0) in -180.0..180.0)
        assertEquals(20.0, GeoUtils.angleDiff(350.0, 10.0), 0.001)
    }
}
