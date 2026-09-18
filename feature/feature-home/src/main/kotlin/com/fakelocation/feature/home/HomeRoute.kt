package com.fakelocation.feature.home

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fakelocation.core.common.model.LatLng
import com.fakelocation.feature.home.map.OsmTileSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/**
 * 单点定位 MVP 主页面。
 *
 * 地图：osmdroid（OpenStreetMap 瓦片，免 Key）
 * 操作流：
 * 1. 申请 ACCESS_FINE_LOCATION 权限
 * 2. 检查模拟位置授权（开发者选项）
 * 3. 长按地图选点
 * 4. 点击「开始模拟」→ 引擎持续注入
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoute(
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var permissionRequested by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // 权限申请
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = HomeViewModel.REQUIRED_PERMISSIONS.filter { result[it] == true }
        viewModel.setPermissionGranted(HomeViewModel.hasAllPermissions(granted))
    }

    // 关键修复（B3）：每次 Activity 回到前台都重检授权与权限。
    // 覆盖「进入页面」与「从系统设置 / 权限弹窗返回」两种场景。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshAuthState(context)
    }

    // 首次进入时主动申请权限；之后由 ON_RESUME 内的 refreshAuthState 维持状态。
    LaunchedEffect(Unit) {
        if (!permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(HomeViewModel.REQUIRED_PERMISSIONS)
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        bottomBar = {
            BottomBar(
                uiState = uiState,
                onStart = viewModel::startMock,
                onStop = viewModel::stopMock,
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            OsmMapContent(
                picked = uiState.pickedTarget,
                onPick = viewModel::setPicked,
            )

            // 顶部：搜索框 + 状态横幅
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                SearchBox(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        viewModel.searchAddress(it)
                    },
                    onClear = {
                        searchQuery = ""
                        viewModel.clearSearchResults()
                    },
                    isSearching = uiState.isSearching,
                )
                Spacer(Modifier.height(8.dp))
                if (uiState.searchResults.isNotEmpty()) {
                    SearchResults(
                        results = uiState.searchResults,
                        onPickResult = viewModel::pickSearchResult,
                    )
                }
                StatusBanner(
                    uiState = uiState,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 右下角：当前位置 FAB
            FloatingActionButton(
                onClick = { viewModel.requestCurrentLocation(context) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "当前位置")
            }
        }
    }
}

/**
 * 顶部地址搜索框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    isSearching: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索地址") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Close, contentDescription = "清空")
                    }
                } else if (isSearching) {
                    // 简单提示：搜索中
                    Box(Modifier.padding(end = 16.dp)) {
                        Text("…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            singleLine = true,
        )
    }
}

/**
 * 搜索结果下拉。
 */
@Composable
private fun SearchResults(
    results: List<GeocodeResult>,
    onPickResult: (GeocodeResult) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            results.forEachIndexed { index, result ->
                if (index > 0) {
                    androidx.compose.material3.HorizontalDivider()
                }
                Surface(
                    onClick = { onPickResult(result) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        text = result.displayName,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(uiState: HomeUiState, modifier: Modifier = Modifier) {
    val (text, color) = when {
        !uiState.hasFineLocationPermission -> "缺少定位权限" to MaterialTheme.colorScheme.error
        !uiState.isAuthorized -> "请到「开发者选项 - 选择模拟位置应用」中授权本 App" to MaterialTheme.colorScheme.error
        uiState.isRunning -> "模拟运行中" to MaterialTheme.colorScheme.primary
        uiState.pickedTarget != null -> "已选点：${"%.5f".format(uiState.pickedTarget.latitude)}, ${"%.5f".format(uiState.pickedTarget.longitude)}" to MaterialTheme.colorScheme.secondary
        else -> "长按地图选择目标位置" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * osmdroid MapView 通过 AndroidView 嵌入 Compose。
 * 持有 pickedMarker 引用以便后续更新/移除。
 */
@Composable
private fun OsmMapContent(
    picked: LatLng?,
    onPick: (LatLng) -> Unit,
) {
    val context = LocalContext.current

    // 首帧先用默认候选源，避免白屏；探测到更合适的源后由下方 LaunchedEffect 切换
    val initialTileSource = remember { OsmTileSources.default() }
    val mapView = remember { createMapView(context, initialTileSource) }
    // 用 Compose state 持有当前 marker 引用，避免每次重挂
    var pickedMarker by remember { mutableStateOf<Marker?>(null) }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    // 初次配置
    LaunchedEffect(Unit) {
        mapView.controller.setZoom(12.0)
        mapView.controller.setCenter(GeoPoint(31.2304, 121.4737)) // 默认上海
    }

    // 关键修复（N1）：osmdroid 默认源 tile.openstreetmap.org 在国内会被 DNS 污染，
    // 瓦片请求全部 ConnectException，地图只剩缓存区域能看，拖到新位置就是空白。
    // 这里在 IO 线程探测一组**同源**候选（OSM 法国 HOT / OSM 德国 / 官方），
    // 切到第一个可达的。三者均为 OSM 数据、ODbL 许可、免 Key、WGS-84 坐标系，
    // 与引擎注入的坐标完全一致，切换后不会有任何偏移。
    LaunchedEffect(Unit) {
        val resolved = withContext(Dispatchers.IO) { OsmTileSources.resolveBlocking() }
        if (resolved !== initialTileSource) {
            mapView.setTileSource(resolved)
            mapView.invalidate()
        }
    }

    // 选点变化 → 移动相机 + 更新 marker
    LaunchedEffect(picked) {
        if (picked == null) {
            // 清掉旧 marker
            pickedMarker?.let { marker ->
                mapView.overlays.remove(marker)
                mapView.invalidate()
            }
            pickedMarker = null
        } else {
            mapView.controller.animateTo(GeoPoint(picked.latitude, picked.longitude))
            // 移除旧 marker，添加新 marker
            pickedMarker?.let { mapView.overlays.remove(it) }
            val marker = Marker(mapView).apply {
                position = GeoPoint(picked.latitude, picked.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Mock 目标"
                snippet = "%.5f, %.5f".format(picked.latitude, picked.longitude)
            }
            mapView.overlays.add(marker)
            pickedMarker = marker
            mapView.invalidate()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            // 长按事件 overlay 只挂一次
            attachLongPressOverlay(view, onPick)
        },
    )
}

private fun createMapView(context: Context, tileSource: ITileSource): MapView = MapView(context).apply {
    setTileSource(tileSource)
    setMultiTouchControls(true)
    isHorizontalMapRepetitionEnabled = false
    isVerticalMapRepetitionEnabled = false
    setUseDataConnection(true)
    // ODbL 许可要求展示数据来源署名。之前缺失，属合规风险。
    overlays.add(CopyrightOverlay(context))
}

private fun attachLongPressOverlay(mapView: MapView, onPick: (LatLng) -> Unit) {
    val tag = "long_press_overlay"
    if (mapView.getTag() == tag) return
    mapView.setTag(tag)

    val receiver = object : MapEventsReceiver {
        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
        override fun longPressHelper(p: GeoPoint?): Boolean {
            p ?: return false
            onPick(LatLng(p.latitude, p.longitude))
            return true
        }
    }
    mapView.overlays.add(0, MapEventsOverlay(receiver))
    mapView.invalidate()
}

@Composable
private fun BottomBar(
    uiState: HomeUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (uiState.pickedTarget == null) {
                    "请先在地图上选点"
                } else {
                    "目标：${"%.5f".format(uiState.pickedTarget.latitude)}, ${"%.5f".format(uiState.pickedTarget.longitude)}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            if (uiState.isRunning) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("停止模拟")
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = uiState.pickedTarget != null
                        && uiState.hasFineLocationPermission
                        && uiState.isAuthorized,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("开始模拟")
                }
            }
        }
    }
}
