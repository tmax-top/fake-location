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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

    /** 进行中的搜索任务。防抖时先取消上一轮，避免旧结果覆盖新结果。 */
    private var searchJob: Job? = null

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
     * 地址搜索。主源 **Photon**，兜底 Nominatim。
     *
     * ## 为什么换掉单一 Nominatim 源（关键修复 N2）
     *
     * 原来只用 `nominatim.openstreetmap.org`。该域名在国内会被 **DNS 污染**：
     * 实测被解析到不可达的 `74.86.142.55`，连接超时后抛 ConnectException，
     * 用户只看到一句原始异常字符串，搜索完全不可用。
     *
     * 现改为双源：
     * 1. **Photon**（`photon.komoot.io`）—— 基于 OSM 数据的免费地理编码服务。
     *    免 Key、实测国内可直连、中文检索质量好（「上海外滩」直接命中「上海外滩美术馆」）
     * 2. **Nominatim** —— 兜底。国外网络或用户挂代理时可用
     *
     * 两者都返回 **WGS-84** 坐标，与引擎注入的坐标系一致，无偏移问题。
     */
    fun searchAddress(query: String) {
        val q = query.trim()
        // 新一轮输入先取消上一轮
        searchJob?.cancel()
        searchJob = null

        if (q.length < MIN_QUERY_LENGTH) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }

        _isSearching.value = true
        searchJob = viewModelScope.launch {
            // 防抖：连续键入只发最后一次请求。既省流量，也避免触发
            // Photon / Nominatim 的频率限制（两者都是志愿者运维的公共服务）
            delay(SEARCH_DEBOUNCE_MS)
            val results = withContext(Dispatchers.IO) { remoteSearch(q) }
            _searchResults.value = results
            _isSearching.value = false
        }
    }

    /**
     * 双源搜索：Photon 主源，**仅在其请求失败（抛异常）时**降级 Nominatim。
     * 必须在 IO 线程调用（会发起阻塞式网络请求）。
     *
     * ## 为什么「返回 0 条」不降级
     *
     * 「查得到但没匹配」是正常业务结果，不是网络故障。若此时降级到 Nominatim
     * （国内被 DNS 污染），用户要白等 8s 连接超时 + 10s 读超时才看到提示 ——
     * 实测用户输入「上海东镇高」就踩到了这个坑。
     */
    private fun remoteSearch(query: String): List<GeocodeResult> {
        val photon = runCatching { photonSearch(query) }
            .onFailure { Log.w(TAG, "Photon 搜索失败", it) }
            .getOrNull()

        if (photon != null) {
            if (photon.isEmpty()) Log.w(TAG, "Photon 无匹配结果")
            return photon
        }

        // 只有 Photon 真正失败才降级（例如被 DNS 污染、被限流）
        Log.w(TAG, "Photon 请求失败，降级 Nominatim")
        val nominatim = runCatching { nominatimSearch(query) }
            .onFailure { Log.w(TAG, "Nominatim 搜索失败", it) }
            .getOrNull()
        if (nominatim != null) return nominatim

        // 两个源都请求失败（而非都返回空）才算网络不可用
        _error.value = "搜索失败：地址服务不可达，请检查网络后重试（可尝试切换 Wi-Fi / 移动数据）"
        return emptyList()
    }

    // region -- Photon（主源）--

    /**
     * Photon（Komoot）搜索，返回 GeoJSON FeatureCollection。
     * 见 https://photon.komoot.io
     */
    private fun photonSearch(query: String): List<GeocodeResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        // 注意：**不要**传 `lang=zh`。Photon 只支持 default / de / en / fr，
        // 传 zh 会直接返回 HTTP 400。不带 lang 时返回 OSM 原始名称，
        // 中国区域的数据本身就是中文（实测「上海外滩」→「上海外滩美术馆」）。
        val urlStr = "$PHOTON_ENDPOINT?q=$encoded&limit=$MAX_SEARCH_RESULTS"
        Log.w(TAG, "Photon request: $urlStr")

        val body = httpGet(urlStr)
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val feature = features.optJSONObject(i) ?: return@mapNotNull null
            // GeoJSON 的坐标顺序是 [经度, 纬度] —— 别弄反
            val coordinates = feature.optJSONObject("geometry")
                ?.optJSONArray("coordinates")
                ?: return@mapNotNull null
            val lon = coordinates.optDouble(0, Double.NaN)
            val lat = coordinates.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return@mapNotNull null

            val props = feature.optJSONObject("properties") ?: JSONObject()
            val displayName = formatPhotonName(props)
            if (displayName.isBlank()) null else GeocodeResult(displayName, LatLng(lat, lon))
        }
    }

    /**
     * 把 Photon 的 `properties` 拼成一行可读地址。
     * 例：`上海外滩美术馆 · 虎丘路 20 · 外滩街道 · 上海市 · 中国`
     */
    private fun formatPhotonName(props: JSONObject): String {
        fun text(key: String): String? =
            props.optString(key).takeIf { it.isNotBlank() && it != "null" }

        val streetLine = text("street")?.let { street ->
            text("housenumber")?.let { "$street $it" } ?: street
        }
        return listOfNotNull(
            text("name"),
            streetLine,
            text("district"),
            text("city"),
            text("state"),
            text("country"),
        ).distinct().joinToString(" · ")
    }

    // endregion

    // region -- Nominatim（兜底）--

    private fun nominatimSearch(query: String): List<GeocodeResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urlStr = "$NOMINATIM_ENDPOINT?q=$encoded&format=json&limit=" +
            "$MAX_SEARCH_RESULTS&addressdetails=1"
        Log.w(TAG, "Nominatim request: $urlStr")

        val body = httpGet(urlStr)
        val array = JSONArray(body)
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

    // endregion

    /**
     * 发 GET 并返回响应体；非 2xx 直接抛异常，由上层 `runCatching` 兜住。
     *
     * User-Agent 对 Nominatim 是**强制**要求（其使用政策规定），Photon 无强制要求但一并发。
     */
    private fun httpGet(urlStr: String): String {
        val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", Locale.getDefault().language)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun clearSearchResults() {
        searchJob?.cancel()
        searchJob = null
        _searchResults.value = emptyList()
        _isSearching.value = false
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

        /** 触发搜索所需的最少字符数。 */
        private const val MIN_QUERY_LENGTH = 2

        /** 键入防抖窗口，毫秒。 */
        private const val SEARCH_DEBOUNCE_MS = 250L

        /**
         * 网络超时。比原来的 5s 放宽 —— 跨境访问 Photon / Nominatim 时
         * 5s 容易在慢链路上误判为失败。
         */
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 10_000

        /** Nominatim 使用政策强制要求可识别的 User-Agent。 */
        private const val USER_AGENT = "FakeLocation-Android/0.2.0"

        /** 主源：Photon（Komoot），基于 OSM 的免费地理编码，免 Key。 */
        private const val PHOTON_ENDPOINT = "https://photon.komoot.io/api/"

        /** 兜底源：Nominatim（国内部分网络会被 DNS 污染，仅作降级）。 */
        private const val NOMINATIM_ENDPOINT = "https://nominatim.openstreetmap.org/search"

        /**
         * 检查所有必需权限是否都已授予。
         * @param granted 已被授权的权限名集合（来自 `shouldShowRequestPermissionRationale` 等接口）
         */
        fun hasAllPermissions(granted: Collection<String>): Boolean {
            return REQUIRED_PERMISSIONS.all { it in granted }
        }
    }
}
