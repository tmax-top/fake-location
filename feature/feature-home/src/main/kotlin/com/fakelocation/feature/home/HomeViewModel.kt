package com.fakelocation.feature.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fakelocation.core.common.model.LatLng
import com.fakelocation.core.mock.MockAuthChecker
import com.fakelocation.core.mock.MockLocationEngine
import com.fakelocation.core.mock.MockState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject

/**
 * 地理编码结果（地址搜索用）。
 */
data class GeocodeResult(
    val displayName: String,
    val latLng: LatLng,
)

/**
 * Home 页面状态。
 */
data class HomeUiState(
    val pickedTarget: LatLng? = null,
    val isRunning: Boolean = false,
    val isAuthorized: Boolean = false,
    val hasFineLocationPermission: Boolean = false,
    val errorMessage: String? = null,
    val searchResults: List<GeocodeResult> = emptyList(),
    val isSearching: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val engine: MockLocationEngine,
    private val authChecker: MockAuthChecker,
) : ViewModel() {

    private val _picked = MutableStateFlow<LatLng?>(null)
    private val _permission = MutableStateFlow(false)
    private val _authorized = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _searchResults = MutableStateFlow<List<GeocodeResult>>(emptyList())
    private val _isSearching = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        _picked,
        engine.state,
        _permission,
        _authorized,
        _error,
        _searchResults,
        _isSearching,
    ) { values ->
        // combine 的 7 参重载走 vararg 版本
        @Suppress("UNCHECKED_CAST")
        val picked = values[0] as LatLng?
        val engineState = values[1] as MockState
        val hasPerm = values[2] as Boolean
        val authorized = values[3] as Boolean
        val error = values[4] as String?
        val results = values[5] as List<GeocodeResult>
        val searching = values[6] as Boolean
        HomeUiState(
            pickedTarget = picked,
            isRunning = engineState is MockState.SinglePointRunning ||
                engineState is MockState.RouteRunning,
            isAuthorized = authorized,
            hasFineLocationPermission = hasPerm,
            errorMessage = error,
            searchResults = results,
            isSearching = searching,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    fun setPicked(target: LatLng?) {
        _picked.value = target
    }

    fun setPermissionGranted(granted: Boolean) {
        _permission.value = granted
    }

    fun setAuthorized(authorized: Boolean) {
        _authorized.value = authorized
    }

    /**
     * 同时刷新 mock 授权状态与运行时定位权限。
     * 建议在 onResume 时调用。
     */
    fun refreshAuthState(context: Context) {
        _authorized.value = authChecker.isAuthorized()
        _permission.value = REQUIRED_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 用 Nominatim（OpenStreetMap 公开搜索 API）搜索地址。
     *
     * 选 Nominatim 而非系统 Geocoder 的原因：
     * - 国内 ROM 的系统 Geocoder 通常基于 Google Play 服务，**阉割版会静默返回空列表**
     *   （不抛异常，调用方完全不知道失败）— 这就是上一版不工作的根因。
     * - Nominatim 是 OSM 公共服务，免 key、无额度限制；osmdroid 反正也要下瓦片，
     *   访问 OSM 域名是通的。
     * - 行为跨设备一致：所有搜索结果都来自同一个后端。
     *
     * 用 [HttpURLConnection]（系统内置）调一次，IO 线程 + 5s timeout。
     * 返回结果为有序（按 Nominatim 相关度排序），最多 [MAX_SEARCH_RESULTS] 条。
     */
    fun searchAddress(@Suppress("UNUSED_PARAMETER") context: Context, query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        // 最低限度 debounce：单字符不搜（避免每个键入都打 Nominatim）
        if (q.length < 2) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        _isSearching.value = true
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                nominatimSearch(q)
            }
            _searchResults.value = results
            _isSearching.value = false
        }
    }

    private fun nominatimSearch(query: String): List<GeocodeResult> = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        // 限制 limit + addressdetails=1（让 display_name 包含详细地址）
        val urlStr =
            "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=" +
                "$MAX_SEARCH_RESULTS&addressdetails=1"
        Log.w(TAG, "Nominatim request: $urlStr")
        val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            // Nominatim 使用策略要求：必须设置 User-Agent，禁用默认 User-Agent（基于 OkHttp）
            setRequestProperty("User-Agent", "FakeLocation-Android/0.2.0")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", Locale.getDefault().language)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Nominatim HTTP $code")
                _error.value = "搜索失败：HTTP $code"
                return@runCatching emptyList()
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            Log.w(TAG, "Nominatim response: ${body.take(200)}…")
            parseNominatimResults(body)
        } finally {
            connection.disconnect()
        }
    }.getOrElse { e ->
        Log.w(TAG, "Nominatim failed", e)
        _error.value = "搜索失败：${e.message ?: "未知错误"}"
        emptyList()
    }

    private fun parseNominatimResults(json: String): List<GeocodeResult> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val lat = obj.optDouble("lat", Double.NaN)
            val lon = obj.optDouble("lon", Double.NaN)
            val name = obj.optString("display_name", "")
            if (lat.isNaN() || lon.isNaN() || name.isEmpty()) {
                null
            } else {
                GeocodeResult(name, LatLng(lat, lon))
            }
        }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }

    /**
     * 选取搜索结果，同时清空结果列表 + 设为 picked（地图会飞过去）。
     */
    fun pickSearchResult(result: GeocodeResult) {
        _picked.value = result.latLng
        _searchResults.value = emptyList()
    }

    /**
     * 用系统 [LocationManager] 拿一个真实位置（GPS / Network fallback）。
     *
     * **不**走 [MockLocationEngine]，因为目标是「定位到当前真实位置」——这正是 mock 的反面。
     * 拿到后设为 picked，地图自动飞过去。
     */
    @SuppressLint("MissingPermission") // 由调用方保证权限
    fun requestCurrentLocation(context: Context) {
        if (!_permission.value) {
            _error.value = "缺少定位权限"
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            _error.value = "系统不支持定位"
            return
        }
        viewModelScope.launch {
            val location = withContext(Dispatchers.IO) {
                runCatching {
                    // 优先取最近的缓存位置
                    val providers = listOf(
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.PASSIVE_PROVIDER,
                    )
                    providers.mapNotNull { provider ->
                        runCatching {
                            if (lm.isProviderEnabled(provider)) {
                                lm.getLastKnownLocation(provider)
                            } else null
                        }.getOrNull()
                    }.maxByOrNull { it.time }
                }.getOrNull()
            }
            if (location != null) {
                _picked.value = LatLng(location.latitude, location.longitude)
            } else {
                _error.value = "无法获取当前位置（请确认 GPS / 网络已开启）"
            }
        }
    }

    fun startMock() {
        val target = _picked.value ?: return
        Log.w(TAG, "startMock: target=$target, perm=${_permission.value}, auth=${_authorized.value}")
        if (!_permission.value) {
            _error.value = "缺少定位权限"
            return
        }
        viewModelScope.launch {
            val result = engine.startAt(target)
            result.onFailure { e ->
                Log.w(TAG, "startMock failed", e)
                _error.value = e.message ?: "启动失败"
            }
        }
    }

    fun stopMock() {
        Log.w(TAG, "stopMock")
        viewModelScope.launch {
            val result = engine.stop()
            result.onFailure { e ->
                Log.w(TAG, "stopMock failed", e)
                _error.value = e.message ?: "停止失败"
            }
        }
    }

    fun consumeError() {
        _error.value = null
    }

    @SuppressLint("InlinedApi")
    companion object {
        private const val TAG = "HomeViewModel"

        val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /** 搜索结果上限，避免渲染过多 marker。 */
        const val MAX_SEARCH_RESULTS: Int = 6

        /**
         * 检查所有必需权限是否都已授予。
         * @param granted 已被授权的权限名集合（来自 `shouldShowRequestPermissionRationale` 等接口）
         */
        fun hasAllPermissions(granted: Collection<String>): Boolean {
            return REQUIRED_PERMISSIONS.all { it in granted }
        }
    }
}
