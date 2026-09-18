package com.fakelocation.core.mock

import android.content.Context
import android.location.LocationManager
import com.fakelocation.core.mock.internal.LegacyLocationApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 探测「本 App 是否已被用户设为模拟位置应用」。
 *
 * ## 设计说明
 *
 * Android 6+ 没有公开 API 直接查询授权状态。
 * 唯一的探针是 `LocationManager.addTestProvider()`：
 * - 抛 `SecurityException` → 未授权
 * - 成功 → 已授权
 *
 * ## 与旧实现的差异
 *
 * 1. **不会留下残留**：探测 add 完后立即 remove
 * 2. **优先清理**：调用前先 remove 可能存在的残留（来自上次崩溃）
 * 3. **统一错误处理**：所有异常一律视为「未授权」，不再误把
 *    `IllegalArgumentException`（provider 已存在）当成已授权
 * 4. **API 分支**：调用 [LegacyLocationApi] 处理 API 31+ 现代化与 API 29/30 兼容
 *
 * ## 线程
 *
 * 该方法会触发系统 binder 调用，约 1~5ms。建议从主线程调用
 * （与 `LocationManager` 其他 API 保持一致），且每次结果保留 500ms+ 缓存。
 */
@Singleton
class MockAuthChecker @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * 当前是否已授权。
     *
     * 副作用：会在 `gps` provider 上做一次 add+remove 的探测动作。
     * 不会产生持久化的 provider 残留。
     */
    fun isAuthorized(): Boolean {
        // 1. 清理历史残留
        runCatching {
            if (locationManager.allProviders.contains(TEST_PROVIDER_GPS)) {
                locationManager.removeTestProvider(TEST_PROVIDER_GPS)
            }
        }
        // 2. 探测授权：add 成功 = 已授权
        val authorized = runCatching {
            LegacyLocationApi.addTestProvider(
                locationManager,
                TEST_PROVIDER_GPS,
                /* requiresNetwork    = */ false,
                /* requiresSatellite  = */ true,
                /* requiresCell       = */ false,
                /* hasMonetaryCost    = */ false,
                /* supportsAltitude   = */ true,
                /* supportsSpeed      = */ true,
                /* supportsBearing    = */ true,
            )
            true
        }.getOrElse { e ->
            // SecurityException → 未授权
            // IllegalArgumentException → 探测失败（参数错等），也视为未授权
            // 其它 → 同上，避免假阳性
            when (e) {
                is SecurityException -> false
                else -> false
            }
        }
        // 3. 清理探测残留
        runCatching {
            if (locationManager.allProviders.contains(TEST_PROVIDER_GPS)) {
                locationManager.removeTestProvider(TEST_PROVIDER_GPS)
            }
        }
        return authorized
    }

    private companion object {
        const val TEST_PROVIDER_GPS = "gps"
    }
}
