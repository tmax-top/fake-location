package com.fakelocation.feature.home.map

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import java.net.HttpURLConnection
import java.net.URL

/**
 * 瓦片源候选与可达性探测。
 *
 * ## 为什么要做这件事（关键修复 N1）
 *
 * osmdroid 默认源 `TileSourceFactory.MAPNIK` 指向 `tile.openstreetmap.org`。
 * 该域名在**国内网络下会被 DNS 污染** —— 域名被解析到一个不可达的 IP，
 * 所有瓦片请求抛 `ConnectException`，地图只有缓存过的区域能显示，
 * 一拖到没缓存的地方就是空白。
 *
 * 实测（2026-09-18，Redmi 24122RKC7C + 小米路由器 / 中国移动）：
 *
 * | 域名 | 解析结果 | 443 连通 |
 * |------|---------|---------|
 * | `tile.openstreetmap.org` | 156.233.67.243（污染） | ❌ ConnectException |
 * | `tile.openstreetmap.fr` | 151.115.132.20 | ✅ HTTP 200 |
 * | `tile.openstreetmap.de` | 65.108.14.58 | ✅ HTTP 200 |
 *
 * 因此这里准备一组**同源候选**：全部基于 OSM 数据、ODbL 许可、免 Key、
 * **WGS-84 坐标系**（与默认 MAPNIK 完全一致，切换后无需任何坐标转换，
 * 也不会引入高德/腾讯那种 GCJ-02 偏移问题）。
 *
 * ## 用法
 *
 * [default] 用于地图首帧（取已缓存结果或首个候选），[resolveBlocking] 在
 * IO 线程探测并返回第一个可达源，结果缓存到进程结束。
 */
internal object OsmTileSources {

    private const val TAG = "OsmTileSources"

    /** ODbL 许可要求展示的版权声明。 */
    private const val COPYRIGHT = "© OpenStreetMap contributors"

    private const val PROBE_TIMEOUT_MS = 4_000
    private const val USER_AGENT = "FakeLocation-Android/0.2.0"

    /**
     * 探测用瓦片：z=12 / x=3429 / y=1677 覆盖上海市区。
     * 用固定索引而非随机，保证探测可复现、可对比。
     */
    private const val PROBE_ZOOM = 12
    private const val PROBE_X = 3429
    private const val PROBE_Y = 1677

    /**
     * OSM France 的 HOT（Humanitarian）样式。实测国内可直连，配色比标准 Mapnik 更醒目。
     * URL 形如 `https://a.tile.openstreetmap.fr/hot/12/3429/1677.png`
     */
    val FRANCE_HOT: OnlineTileSourceBase = XYTileSource(
        "OSMFranceHOT",
        0, 19, 256, ".png",
        arrayOf("https://a.tile.openstreetmap.fr/hot/"),
        COPYRIGHT,
    )

    /**
     * OSM 德国镜像，标准 Mapnik 配色。
     * URL 形如 `https://a.tile.openstreetmap.de/12/3429/1677.png`
     */
    val GERMANY: OnlineTileSourceBase = XYTileSource(
        "OSMGermany",
        0, 18, 256, ".png",
        arrayOf("https://a.tile.openstreetmap.de/"),
        COPYRIGHT,
    )

    /**
     * 官方源。国内多数网络不可达，仅作兜底（例如用户挂着代理时反而可用）。
     */
    val OFFICIAL: OnlineTileSourceBase = TileSourceFactory.MAPNIK

    /** 按优先级排列的候选源。 */
    val candidates: List<OnlineTileSourceBase> = listOf(FRANCE_HOT, GERMANY, OFFICIAL)

    @Volatile
    private var resolved: ITileSource? = null

    /**
     * 保护首次探测的锁。
     *
     * 用协程 [Mutex] 而非 `synchronized` —— 探测本身是阻塞式网络请求（约 1s+），
     * 持有 JVM 监视器会被运行时记为 `Long monitor contention` 警告，污染日志。
     */
    private val probeMutex = Mutex()

    /**
     * 供地图首帧使用的源。
     *
     * 已探测过则返回探测结果；否则返回首个候选 —— 这样首帧不会白屏，
     * 探测完如果发现首个候选不可达会由调用方切走。
     */
    fun default(): ITileSource = resolved ?: candidates.first()

    /**
     * 依次探测 [candidates]，返回第一个可达的瓦片源并缓存结果。
     *
     * 内部是阻塞式网络请求，**请在 `Dispatchers.IO` 上调用**。
     * 任何失败都会降级为默认源，不会抛异常。
     */
    suspend fun resolveBlocking(): ITileSource {
        resolved?.let { return it }
        // 加锁：Compose 首次组合可能触发两次，避免重复发探测请求。
        // 双重检查：拿到锁后再看一次缓存，先到者探测完后后来者直接复用。
        return probeMutex.withLock {
            resolved ?: probe().also { resolved = it }
        }
    }

    private fun probe(): ITileSource {
        val probeIndex = MapTileIndex.getTileIndex(PROBE_ZOOM, PROBE_X, PROBE_Y)
        for (source in candidates) {
            val url = runCatching { source.getTileURLString(probeIndex) }.getOrNull()
            if (url.isNullOrBlank()) continue

            if (isReachable(url)) {
                Log.w(TAG, "瓦片源探测成功: ${source.name()} ($url)")
                return source
            }
            Log.w(TAG, "瓦片源不可达: ${source.name()} ($url)")
        }

        Log.w(TAG, "所有候选瓦片源均不可达，降级为 ${candidates.first().name()}")
        return candidates.first()
    }

    /**
     * 发一个只取首字节的 GET，判断瓦片源是否可达。
     */
    private fun isReachable(urlStr: String): Boolean = runCatching {
        val connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = PROBE_TIMEOUT_MS
            readTimeout = PROBE_TIMEOUT_MS
            // 只探测首字节，避免真的把瓦片拉下来
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }.getOrElse { e ->
        Log.w(TAG, "探测异常: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}
