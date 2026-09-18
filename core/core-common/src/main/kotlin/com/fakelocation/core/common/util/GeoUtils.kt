package com.fakelocation.core.common.util

import kotlin.math.PI

/**
 * 地理工具方法（纯函数，无 Android 依赖）。
 */
object GeoUtils {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * 方位角：from -> to 的方向角，正北 0°，顺时针 0~360°。
     */
    fun bearing(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Double {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLon = Math.toRadians(toLng - fromLng)
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        val brng = Math.atan2(y, x)
        return (Math.toDegrees(brng) + 360.0) % 360.0
    }

    /**
     * 把 km/h 转为 m/s。
     */
    fun kmhToMps(kmh: Double): Double = kmh * 1000.0 / 3600.0

    /**
     * 把 m/s 转为 km/h。
     */
    fun mpsToKmh(mps: Double): Double = mps * 3600.0 / 1000.0

    /**
     * 距离（米），Haversine。
     */
    fun haversineMeters(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2).let { it * it }
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /**
     * 角度归一化到 [0, 360)。
     */
    fun normalizeBearing(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /**
     * 角度差（to - from），结果归一化到 (-180, 180]。
     */
    fun angleDiff(from: Double, to: Double): Double {
        var diff = (to - from) % 360.0
        if (diff > 180.0) diff -= 360.0
        if (diff <= -180.0) diff += 360.0
        return diff
    }

    @Suppress("unused")
    private val UNUSED = PI
}
