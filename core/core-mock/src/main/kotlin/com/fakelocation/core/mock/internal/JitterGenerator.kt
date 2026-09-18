package com.fakelocation.core.mock.internal

import com.fakelocation.core.common.model.LatLng
import com.fakelocation.core.common.util.GeoUtils
import kotlin.random.Random

/**
 * 在原坐标附近做小范围随机偏移，避免被识别为「静止点」。
 *
 * 范围默认 ±3m（圆形），保证抖动后仍在合理误差内。
 */
class JitterGenerator(
    private val random: Random = Random.Default,
    private val maxMeters: Double = 3.0,
) {
    fun apply(origin: LatLng): LatLng {
        if (maxMeters <= 0.0) return origin
        // 在 [0, maxMeters] 距离上均匀分布
        val distance = random.nextDouble(0.0, maxMeters)
        val bearing = random.nextDouble(0.0, 360.0)
        return origin.destination(distance, bearing)
    }
}

@Suppress("unused")
internal fun deg2rad(d: Double) = d * Math.PI / 180.0

@Suppress("unused")
internal fun bearingShim(a: LatLng, b: LatLng) =
    GeoUtils.bearing(a.latitude, a.longitude, b.latitude, b.longitude)
