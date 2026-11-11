package com.fakelocation.core.mock

import android.content.Context
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
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
 * ## 关键修复（A1）：探针绝不能叫 `gps`
 *
 * 旧实现用 `gps` 作为探针名，执行「remove → add → remove」。
 * 但引擎注入时用的 test provider **名字同样是 `gps`**（系统 real gps 就叫这个
 * 名字，test provider 是对它的 override）。因此每次探测都会把引擎正在使用的
 * provider 连带删掉：
 *
 * 1. 用户点「开始模拟」→ `setupProviders()` 注册 gps / network 并注入成功
 * 2. 切到高德再切回 → `ON_RESUME` → `refreshAuthState()` → `isAuthorized()`
 * 3. 探测 `removeTestProvider("gps")` → **引擎的 gps 通道被删除**
 * 4. 后续 `setTestProviderLocation("gps", …)` 抛 `IllegalArgumentException`，
 *    被 `pushLocation()` 的 `runCatching` 静默吞掉 → 假位置只「闪一下」就消失
 *
 * 系统 `dumpsys location` 里的三连日志
 * `gps removed mock provider override / added / removed` 就是这么来的，
 * 同时每次都会让系统重弹一次「正在提供模拟位置」通知。
 *
 * `addTestProvider()` 的 appop 校验发生在系统服务入口，**与 provider 名无关**，
 * 所以这里改用一个专属探针名即可，完全不碰 gps / network。
 *
 * ## 关键修复（A2）：结果缓存
 *
 * `ON_RESUME` 可能高频触发（权限弹窗、从设置返回、Compose 重组），
 * 每次都做一次 binder 探测既浪费又会扰动系统。按 KDoc 原建议缓存 500ms。
 *
 * ## 线程
 *
 * 该方法会触发系统 binder 调用，约 1~5ms，主线程调用安全。
 */
@Singleton
class MockAuthChecker @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val cacheLock = Any()

    @Volatile
    private var cachedResult: Boolean? = null

    @Volatile
    private var cachedAtElapsedMs: Long = 0L

    /**
     * 当前是否已授权。
     *
     * 副作用：仅在专属探针 provider 上做一次 add + remove，**不会影响
     * gps / network**，也不会留下持久残留。
     *
     * 注意：引擎正在运行时，调用方（`HomeViewModel.refreshAuthState`）应直接
     * 跳过本方法 —— 能跑起来本身就证明有授权，且可避免任何 provider 扰动。
     */
    fun isAuthorized(): Boolean {
        val now = SystemClock.elapsedRealtime()
        synchronized(cacheLock) {
            val cached = cachedResult
            if (cached != null && now - cachedAtElapsedMs < CACHE_TTL_MS) {
                return cached
            }
        }

        val result = probe()
        synchronized(cacheLock) {
            cachedResult = result
            cachedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        return result
    }

    /** 强制丢弃缓存（例如用户从「开发者选项」返回后主动刷新）。 */
    fun invalidateCache() {
        synchronized(cacheLock) {
            cachedResult = null
            cachedAtElapsedMs = 0L
        }
    }

    private fun probe(): Boolean {
        // 1. 清理上一次可能留下的探针残留（不存在时 remove 会抛异常，忽略即可）
        runCatching { locationManager.removeTestProvider(PROBE_PROVIDER) }

        // 2. add 成功 = appop 已授权。任何异常一律按「未授权」处理，避免假阳性
        val authorized = runCatching {
            LegacyLocationApi.addTestProvider(
                locationManager,
                PROBE_PROVIDER,
                /* requiresNetwork    = */ false,
                /* requiresSatellite  = */ false,
                /* requiresCell       = */ false,
                /* hasMonetaryCost    = */ false,
                /* supportsAltitude   = */ false,
                /* supportsSpeed      = */ false,
                /* supportsBearing    = */ false,
            )
            true
        }.getOrElse { e ->
            Log.w(TAG, "授权探测失败（视为未授权）: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

        // 3. 立即清理探针，绝不留痕
        runCatching { locationManager.removeTestProvider(PROBE_PROVIDER) }

        Log.w(TAG, "授权探测结果=$authorized (probe=$PROBE_PROVIDER)")
        return authorized
    }

    private companion object {
        const val TAG = "MockAuthChecker"

        /**
         * 专属探针名。**不要改成 `gps` / `network` / `fused`** ——
         * 那会覆盖引擎正在使用的 test provider（详见类 KDoc 关键修复 A1）。
         */
        const val PROBE_PROVIDER = "fake_location_auth_probe"

        const val CACHE_TTL_MS = 500L
    }
}
