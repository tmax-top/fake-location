package com.fakelocation.core.mock

import com.fakelocation.core.common.model.LatLng

/**
 * 引擎状态机。
 */
sealed interface MockState {
    /** 未运行。 */
    data object Idle : MockState

    /** 单点模式运行中。 */
    data class SinglePointRunning(val target: LatLng) : MockState

    /** 路线模式运行中。 */
    data class RouteRunning(val progress: Float) : MockState

    /** 正在初始化。 */
    data class Starting(val target: LatLng) : MockState

    /** 正在停止（清理 provider）。 */
    data object Stopping : MockState
}
