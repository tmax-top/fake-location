package com.fakelocation.core.mock

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.fakelocation.core.common.model.LatLng
import com.fakelocation.core.mock.internal.JitterGenerator
import com.fakelocation.core.mock.internal.LegacyLocationApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 默认实现。
 *
 * 同时 mock `gps` + `network` 两个 provider，弥补部分 App 只读 network 的盲区。
 * 内部用 `synchronized` 锁保证 [startAt]/[stop] 串行化。
 */
@Singleton
class MockLocationEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authChecker: MockAuthChecker,
) : MockLocationEngine {

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var injectJob: Job? = null
    // 使用 Mutex 替代 synchronized：suspend 友好，startAt/stop 串行化，
    // 同时支持 cancelAndJoin() 等待注入循环真正结束再 remove provider。
    private val mutex = Mutex()

    private val _state = MutableStateFlow<MockState>(MockState.Idle)
    override val state: StateFlow<MockState> = _state.asStateFlow()

    @Volatile
    private var config: MockConfig = MockConfig()

    /**
     * 更新引擎配置。下一次 startAt 时生效。
     *
     * 关键修复（M3）：非法值通过 [MockConfig.sanitizedCopy] 钳制到合法区间，
     * 避免 [MockConfig] 主构造的 [require] 抛 IllegalArgumentException 崩溃进程。
     */
    fun updateConfig(newConfig: MockConfig) {
        config = newConfig.sanitizedCopy()
    }

    /**
     * 委托给 [MockAuthChecker]，避免重复实现探测逻辑。
     */
    override fun isAuthorized(): Boolean = authChecker.isAuthorized()

    @SuppressLint("MissingPermission") // 由调用方保证权限
    override suspend fun startAt(target: LatLng): kotlin.Result<Unit> = mutex.withLock {
        Log.w(TAG, "startAt($target) entry")
        // 0. 校验运行时权限
        if (!hasFineLocationPermission()) {
            Log.w(TAG, "startAt: missing ACCESS_FINE_LOCATION")
            return@withLock kotlin.Result.failure(
                SecurityException("ACCESS_FINE_LOCATION not granted")
            )
        }

        // 1. 切换状态
        _state.value = MockState.Starting(target)

        // 2. 注册 provider
        val setupResult = runCatching { setupProviders() }
        if (setupResult.isFailure) {
            val e = setupResult.exceptionOrNull()
            Log.w(TAG, "setupProviders failed", e)
            _state.value = MockState.Idle
            return@withLock kotlin.Result.failure(
                e ?: IllegalStateException("setupProviders failed")
            )
        }
        Log.w(TAG, "setupProviders ok, providers=${locationManager.allProviders}")

        // 3. 等旧协程彻底退出，再启动新的（避免并发注入）
        injectJob?.cancelAndJoin()
        injectJob = scope.launch { injectLoop(target) }

        _state.value = MockState.SinglePointRunning(target)
        kotlin.Result.success(Unit)
    }

    override suspend fun stop(): kotlin.Result<Unit> = mutex.withLock {
        _state.value = MockState.Stopping
        // 关键修复（S1）：必须 cancelAndJoin() 等待注入循环真正结束。
        // 否则 removeProviders() 会在协程下一次 delay 之前执行，注入循环
        // 之后的 setTestProviderLocation 会因 provider 已被 remove 而抛
        // IllegalArgumentException（被 runCatching 吞，但状态机混乱）。
        injectJob?.cancelAndJoin()
        injectJob = null

        val teardown = runCatching { removeProviders() }
        _state.value = MockState.Idle
        teardown
    }

    /**
     * 清理（App 退出时调用）。
     */
    fun shutdown() {
        scope.cancel()
    }

    // region -- provider lifecycle --

    private fun setupProviders() {
        Log.w(TAG, "setupProviders: allProviders(before) = ${locationManager.allProviders}")
        // 关键修复（S2）：App 上次崩溃 / 被系统强杀时，stop() 可能没机会执行，
        // 留下 test provider 残留。如果不清理，后续 addTestProvider 会抛
        // "provider already exists" 异常。MockAuthChecker 也有类似清理，
        // 这里再做一次以防 startAt() 是入口（用户没经过引导页 / 授权检测）。
        runCatching {
            if (locationManager.allProviders.contains(TEST_PROVIDER_GPS)) {
                locationManager.removeTestProvider(TEST_PROVIDER_GPS)
            }
        }
        runCatching {
            if (locationManager.allProviders.contains(TEST_PROVIDER_NETWORK)) {
                locationManager.removeTestProvider(TEST_PROVIDER_NETWORK)
            }
        }

        // 关键修复：永远调 addTestProvider，让系统自己决定（覆盖 real provider / 黑名单 / 已注册）。
        // 之前用 if (!allProviders.contains(...)) 跳过时，如果系统 real gps 已存在，
        // 名字 "gps" 已经在 allProviders 里 → if 跳过 → 实际没注册成 test provider →
        // setTestProviderStatus / setTestProviderLocation 抛 "is not a test provider"。
        Log.w(TAG, "addTestProvider(gps)…")
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
        Log.w(TAG, "addTestProvider(gps) ok, setTestProviderEnabled…")
        locationManager.setTestProviderEnabled(TEST_PROVIDER_GPS, true)
        Log.w(TAG, "setTestProviderEnabled(gps) ok, setTestProviderStatus…")
        LegacyLocationApi.setTestProviderStatus(locationManager, TEST_PROVIDER_GPS)
        Log.w(TAG, "setTestProviderStatus(gps) ok")

        Log.w(TAG, "addTestProvider(network)…")
        LegacyLocationApi.addTestProvider(
            locationManager,
            TEST_PROVIDER_NETWORK,
            /* requiresNetwork    = */ true,
            /* requiresSatellite  = */ false,
            /* requiresCell       = */ true,
            /* hasMonetaryCost    = */ false,
            /* supportsAltitude   = */ true,
            /* supportsSpeed      = */ true,
            /* supportsBearing    = */ true,
        )
        Log.w(TAG, "addTestProvider(network) ok, setTestProviderEnabled…")
        locationManager.setTestProviderEnabled(TEST_PROVIDER_NETWORK, true)
        Log.w(TAG, "setTestProviderEnabled(network) ok, setTestProviderStatus…")
        LegacyLocationApi.setTestProviderStatus(locationManager, TEST_PROVIDER_NETWORK)
        Log.w(TAG, "setTestProviderStatus(network) ok, allProviders(after) = ${locationManager.allProviders}")
    }

    private fun removeProviders() {
        runCatching {
            if (locationManager.allProviders.contains(TEST_PROVIDER_GPS)) {
                locationManager.removeTestProvider(TEST_PROVIDER_GPS)
            }
        }
        runCatching {
            if (locationManager.allProviders.contains(TEST_PROVIDER_NETWORK)) {
                locationManager.removeTestProvider(TEST_PROVIDER_NETWORK)
            }
        }
    }

    // endregion

    // region -- inject loop --

    private suspend fun injectLoop(target: LatLng) {
        val periodMs = (1000.0 / config.frequencyHz).roundToLong().coerceAtLeast(50L)
        val jitter = JitterGenerator(Random.Default, config.jitterMeters)
        var lastBearing = 0.0

        while (scope.isActive) {
            val jittered = jitter.apply(target)
            pushLocation(jittered, lastBearing)

            lastBearing = (lastBearing + Random.nextDouble(-15.0, 15.0) + 360.0) % 360.0
            delay(periodMs)
        }
    }

    private fun pushLocation(at: LatLng, bearing: Double) {
        val now = System.currentTimeMillis()
        runCatching {
            val gps = Location(TEST_PROVIDER_GPS).apply {
                latitude = at.latitude
                longitude = at.longitude
                accuracy = config.accuracyMeters
                altitude = 0.0
                this.bearing = bearing.toFloat()
                speed = 0.0f
                time = now
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            locationManager.setTestProviderLocation(TEST_PROVIDER_GPS, gps)

            val net = Location(TEST_PROVIDER_NETWORK).apply {
                latitude = at.latitude
                longitude = at.longitude
                accuracy = config.accuracyMeters
                altitude = 0.0
                this.bearing = bearing.toFloat()
                speed = 0.0f
                time = now
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            locationManager.setTestProviderLocation(TEST_PROVIDER_NETWORK, net)
        }.onFailure { Log.w(TAG, "pushLocation failed", it) }
    }

    // endregion

    private fun hasFineLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "MockLocationEngine"
        const val TEST_PROVIDER_GPS = "gps"
        const val TEST_PROVIDER_NETWORK = "network"
    }
}

