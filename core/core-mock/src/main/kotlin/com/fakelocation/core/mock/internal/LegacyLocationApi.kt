@file:Suppress("DEPRECATION")

package com.fakelocation.core.mock.internal

import android.annotation.SuppressLint
import android.location.Criteria
import android.location.LocationManager
import android.location.LocationProvider
import android.location.provider.ProviderProperties
import android.os.Build

/**
 * 集中封装 [LocationManager] 的 test provider API。
 *
 * ## 为什么需要这个 helper
 *
 * minSdk = 29 时无法完全摆脱 deprecated API：
 * - `Criteria`、`Criteria.POWER_LOW`、`Criteria.ACCURACY_HIGH` 在 API 31+ 被 [ProviderProperties] 替代
 * - `LocationProvider.AVAILABLE` 同理
 * - `setTestProviderStatus(...)` 在 API 31+ 仍然 deprecated（没有非 deprecated 替代）
 *
 * 策略：
 * - **API 31+** 走 modern 路径，零 deprecation
 * - **API 29/30** 走 legacy 路径，单独标注 `@Suppress("DEPRECATION")`，避免污染主流程
 *
 * 这样编译输出干净，主流程（[com.fakelocation.core.mock.MockLocationEngineImpl] /
 * [com.fakelocation.core.mock.MockAuthChecker]）不需要任何 deprecation 抑制。
 */
internal object LegacyLocationApi {

    /**
     * 注册一个 test provider。
     *
     * 行为等价于 `LocationManager.addTestProvider(...)`：
     * - 成功 → provider 出现在 [LocationManager.allProviders] 中
     * - 失败 → 抛 `SecurityException`（未授权）或 `IllegalArgumentException`（已存在）
     */
    fun addTestProvider(
        locationManager: LocationManager,
        provider: String,
        requiresNetwork: Boolean,
        requiresSatellite: Boolean,
        requiresCell: Boolean,
        hasMonetaryCost: Boolean,
        supportsAltitude: Boolean,
        supportsSpeed: Boolean,
        supportsBearing: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addModern(
                locationManager, provider,
                requiresNetwork, requiresSatellite, requiresCell, hasMonetaryCost,
                supportsAltitude, supportsSpeed, supportsBearing,
            )
        } else {
            addLegacy(
                locationManager, provider,
                requiresNetwork, requiresSatellite, requiresCell, hasMonetaryCost,
                supportsAltitude, supportsSpeed, supportsBearing,
            )
        }
    }

    /**
     * 标记 test provider 为 AVAILABLE 状态。
     *
     * 部分 App 会读取 provider status 来判断是否可用，因此必须设置。
     */
    fun setTestProviderStatus(locationManager: LocationManager, provider: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setStatusModern(locationManager, provider)
        } else {
            setStatusLegacy(locationManager, provider)
        }
    }

    @SuppressLint("NewApi")
    private fun addModern(
        lm: LocationManager,
        name: String,
        requiresNetwork: Boolean,
        requiresSatellite: Boolean,
        requiresCell: Boolean,
        hasMonetaryCost: Boolean,
        supportsAltitude: Boolean,
        supportsSpeed: Boolean,
        supportsBearing: Boolean,
    ) {
        // 关键修复：去掉 `if (allProviders.contains(name)) return` 短路。
        // 系统 real gps / network provider 名字就叫 "gps" / "network"，已在 allProviders 中。
        // 之前这里直接 return → 后续 setTestProviderEnabled 抛 "is not a test provider"。
        // 现在每次都强制 addTestProvider（让系统决定是否允许覆盖 real provider）。
        val props = ProviderProperties.Builder()
            .setHasNetworkRequirement(requiresNetwork)
            .setHasSatelliteRequirement(requiresSatellite)
            .setHasCellRequirement(requiresCell)
            .setHasMonetaryCost(hasMonetaryCost)
            .setHasAltitudeSupport(supportsAltitude)
            .setHasSpeedSupport(supportsSpeed)
            .setHasBearingSupport(supportsBearing)
            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
            .setAccuracy(ProviderProperties.ACCURACY_FINE)
            .build()
        lm.addTestProvider(name, props)
    }

    @Suppress("DEPRECATION")
    private fun addLegacy(
        lm: LocationManager,
        name: String,
        requiresNetwork: Boolean,
        requiresSatellite: Boolean,
        requiresCell: Boolean,
        hasMonetaryCost: Boolean,
        supportsAltitude: Boolean,
        supportsSpeed: Boolean,
        supportsBearing: Boolean,
    ) {
        // 关键修复：同上 —— 不再短路。
        lm.addTestProvider(
            name,
            requiresNetwork, requiresSatellite, requiresCell, hasMonetaryCost,
            supportsAltitude, supportsSpeed, supportsBearing,
            Criteria.POWER_LOW, Criteria.ACCURACY_HIGH,
        )
    }

    @SuppressLint("NewApi")
    private fun setStatusModern(lm: LocationManager, name: String) {
        // API 31+ 没有 setTestProviderStatus 的非 deprecated 替代；旧方法在新设备上行为完全等价。
        setStatusDeprecated(lm, name)
    }

    @Suppress("DEPRECATION")
    private fun setStatusLegacy(lm: LocationManager, name: String) {
        setStatusDeprecated(lm, name)
    }

    @Suppress("DEPRECATION")
    private fun setStatusDeprecated(lm: LocationManager, name: String) {
        lm.setTestProviderStatus(
            name,
            LocationProvider.AVAILABLE,
            null,
            System.currentTimeMillis(),
        )
    }
}
