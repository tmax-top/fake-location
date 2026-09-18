package com.fakelocation.core.common.model

/**
 * 运动模式，对应不同的默认速度。
 */
enum class MovementMode(val defaultSpeedKmh: Double, val displayName: String) {
    WALK(defaultSpeedKmh = 5.0, displayName = "步行"),
    BIKE(defaultSpeedKmh = 15.0, displayName = "骑行"),
    DRIVE(defaultSpeedKmh = 40.0, displayName = "驾车"),
    CUSTOM(defaultSpeedKmh = 10.0, displayName = "自定义");

    fun speedMps(): Double = defaultSpeedKmh * 1000.0 / 3600.0
}
