package com.fakelocation.core.common.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 经纬度坐标点，使用 WGS84 坐标系。
 *
 * @param latitude  纬度，范围 -90.0 ~ 90.0
 * @param longitude 经度，范围 -180.0 ~ 180.0
 */
data class LatLng(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "longitude out of range: $longitude" }
    }

    /**
     * Haversine 球面距离（米）。
     * 精度 < 0.1%，适合短距离（< 100 km）。
     */
    fun distanceTo(other: LatLng): Double {
        val r = 6_371_000.0 // 地球半径（米）
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2).pow2() + cos(lat1) * cos(lat2) * sin(dLon / 2).pow2()
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * 从本点出发，按距离与方向角 (bearing) 推算另一点。
     *
     * @param distanceMeters 距离（米）
     * @param bearingDegrees 方向角，正北 0°，顺时针增加
     */
    fun destination(distanceMeters: Double, bearingDegrees: Double): LatLng {
        val r = 6_371_000.0
        val brng = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(latitude)
        val lon1 = Math.toRadians(longitude)
        val dr = distanceMeters / r

        val lat2 = Math.asin(sin(lat1) * cos(dr) + cos(lat1) * sin(dr) * cos(brng))
        val lon2 = lon1 + atan2(
            sin(brng) * sin(dr) * cos(lat1),
            cos(dr) - sin(lat1) * sin(lat2),
        )
        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    private fun Double.pow2() = this * this
}
