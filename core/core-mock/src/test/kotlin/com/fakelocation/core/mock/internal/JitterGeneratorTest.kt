package com.fakelocation.core.mock.internal

import com.fakelocation.core.common.model.LatLng
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JitterGeneratorTest {

    @Test
    fun `jitter within configured range`() {
        val origin = LatLng(31.2304, 121.4737) // 上海
        val gen = JitterGenerator(Random(42), maxMeters = 3.0)

        repeat(1000) {
            val jittered = gen.apply(origin)
            val distance = origin.distanceTo(jittered)
            // 允许浮点误差 5cm
            assertTrue(distance <= 3.5, "jittered distance $distance > 3.5m")
        }
    }

    @Test
    fun `jitter zero range returns original`() {
        val origin = LatLng(0.0, 0.0)
        val gen = JitterGenerator(maxMeters = 0.0)
        assertEquals(origin, gen.apply(origin))
    }

    @Test
    fun `jitter covers area over many samples`() {
        val origin = LatLng(0.0, 0.0)
        val gen = JitterGenerator(Random(1), maxMeters = 50.0)
        var maxFound = 0.0
        repeat(5000) {
            val d = origin.distanceTo(gen.apply(origin))
            if (d > maxFound) maxFound = d
        }
        // 5000 次采样应能覆盖大部分 50m 范围
        assertTrue(maxFound > 30.0, "expected max distance > 30m, got $maxFound")
    }
}
