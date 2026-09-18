package com.fakelocation.core.mock

import com.fakelocation.core.common.model.LatLng
import com.fakelocation.core.common.model.MovementMode

/**
 * 引擎配置。
 *
 * ## 边界保护
 *
 * 主构造用 [require] 校验（拒绝非法值构造），但运行时热更新场景下非法值不应
 * 引发 IAE 导致进程崩溃。提供 [sanitize] / [sanitizedCopy] 把越界值钳制到合法
 * 区间，供 [MockLocationEngineImpl.updateConfig] 等热更新路径使用。
 */
data class MockConfig(
    /** 注入频率（Hz），合法范围 [MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ]。 */
    val frequencyHz: Float = 1.0f,
    /** 静止时的随机抖动范围（米），合法范围 [0, MAX_JITTER_METERS]。 */
    val jitterMeters: Double = 3.0,
    /** 位置精度（米），必须 > 0。 */
    val accuracyMeters: Float = 5.0f,
    /** 运动模式（影响默认速度，单点模式下不直接使用）。 */
    val movementMode: MovementMode = MovementMode.WALK,
    /** 自定义速度（m/s），仅 CUSTOM 模式生效。 */
    val customSpeedMps: Double = MovementMode.WALK.speedMps(),
) {
    init {
        require(frequencyHz in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ) {
            "frequencyHz out of range [$MIN_FREQUENCY_HZ, $MAX_FREQUENCY_HZ]: $frequencyHz"
        }
        require(jitterMeters in 0.0..MAX_JITTER_METERS) {
            "jitterMeters out of range [0, $MAX_JITTER_METERS]: $jitterMeters"
        }
        require(accuracyMeters > 0f) { "accuracyMeters must > 0" }
    }

    /**
     * 把越界值钳制到合法区间，返回新的 [MockConfig]。
     *
     * 用于热更新场景（如设置页传入非法值），避免 [require] 抛 IAE 导致崩溃。
     */
    fun sanitizedCopy(
        frequencyHz: Float = this.frequencyHz,
        jitterMeters: Double = this.jitterMeters,
        accuracyMeters: Float = this.accuracyMeters,
        movementMode: MovementMode = this.movementMode,
        customSpeedMps: Double = this.customSpeedMps,
    ): MockConfig = copy(
        frequencyHz = frequencyHz.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ),
        jitterMeters = jitterMeters.coerceIn(0.0, MAX_JITTER_METERS),
        accuracyMeters = accuracyMeters.coerceAtLeast(MIN_ACCURACY_METERS),
        movementMode = movementMode,
        customSpeedMps = customSpeedMps,
    )

    companion object {
        const val MIN_FREQUENCY_HZ: Float = 0.5f
        const val MAX_FREQUENCY_HZ: Float = 5.0f
        const val MAX_JITTER_METERS: Double = 50.0
        const val MIN_ACCURACY_METERS: Float = 0.1f
    }
}

