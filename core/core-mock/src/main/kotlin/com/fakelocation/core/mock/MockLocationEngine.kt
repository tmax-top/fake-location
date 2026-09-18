package com.fakelocation.core.mock

import com.fakelocation.core.common.model.LatLng

/**
 * 引擎对外暴露的接口（单点 MVP 实现）。
 *
 * - 状态以 [state] Flow 形式对外发布
 * - 调用 [startAt] 开始单点模拟，[stop] 停止
 * - [isAuthorized] 检查是否已被系统授权为「模拟位置应用」
 */
interface MockLocationEngine {
    val state: kotlinx.coroutines.flow.StateFlow<MockState>

    /**
     * 检查当前 App 是否已被用户在「开发者选项 - 选择模拟位置应用」中授权。
     * 始终返回 true 表示已授权；false 表示未授权，需要引导用户去系统设置。
     *
     * 注意：在 Android 6.0 (API 23) 之后，此检查应通过 Settings.Secure 或
     * 简单地 addTestProvider 抛 SecurityException 来反推。
     */
    fun isAuthorized(): Boolean

    /**
     * 在指定坐标开始单点模拟。已运行时会先停止再重启。
     */
    suspend fun startAt(target: LatLng): kotlin.Result<Unit>

    /**
     * 停止当前模拟，恢复真实位置。
     */
    suspend fun stop(): kotlin.Result<Unit>
}
