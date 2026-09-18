# API Key 与第三方服务说明

> 最后更新：2026-09-18

## 结论：本 App 不需要任何 API Key

`local.properties` 只需配置 `sdk.dir`（Android SDK 路径）。**没有** API Key、没有客户端密钥、没有 `google-services.json`。

---

## 为什么不需要

最初规划使用 Google Maps SDK + Places Autocomplete + Directions API，这些都需要 API Key 且存在配额、包名/SHA1 限制、以及国内网络不可达的问题。v0.1 实施中全部替换为免 Key 的开源方案：

| 原方案 | 现方案 | 许可证 / 使用条款 |
|-------|-------|-----------------|
| Google Maps SDK（地图渲染） | **osmdroid 6.1.20** + OpenStreetMap 瓦片 | osmdroid MIT；OSM 瓦片数据 ODbL，需遵守 [瓦片使用政策](https://operations.osmfoundation.org/policies/tiles/) |
| Google Places Autocomplete（地址搜索） | **Nominatim** 公共 API（`nominatim.openstreetmap.org`） | OSM 数据 ODbL，遵守 [Nominatim 使用政策](https://operations.osmfoundation.org/policies/nominatim/)：**必须设置自定义 `User-Agent`**、禁止高频批量请求 |
| Google Directions API（路线规划） | 计划 v0.4 改用 **OSRM 公共 API**（尚未实现） | OSRM BSD；公共 demo server 有使用限制 |

### 代码中的实现位置

- 地图瓦片源：`feature-home/src/main/kotlin/com/fakelocation/feature/home/HomeRoute.kt` → `TileSourceFactory.MAPNIK`
- 地址搜索：`feature-home/.../HomeViewModel.kt` → `nominatimSearch()`，已按政策设置 `User-Agent: FakeLocation-Android/0.2.0`

---

## 使用第三方 OSM 公共服务时的注意事项

1. **必须设置可识别的 `User-Agent`**（当前已设置），不要用默认值或伪造浏览器 UA。
2. **不要在 UI 里做无限频率的请求**：Nomatim 目前**没有防抖**（每次键入都发请求），正式对外发布前必须补 250ms 防抖 + 最小请求间隔。
3. **不要大批量抓取**：瓦片和 Nominatim 都是志愿者出资运维的公共服务。若日活上来，应自建瓦片服务或切换到商业地图服务。
4. **署名要求**：使用 OSM 数据需在 App 内展示「© OpenStreetMap contributors」。**当前未实现**，上架前必补（见 `release-checklist.md`）。

---

## 如果将来要引入需要 Key 的服务

若后续决定切回 Google Maps 或引入任何需要凭证的服务，按以下方式做：

1. **不要把 Key 写进 `AndroidManifest.xml` 或任何受版本控制的文件**。
2. 在项目根 `local.properties`（已被 `.gitignore` 忽略）中写入：

   ```properties
   sdk.dir=/path/to/Android/sdk
   MAPS_API_KEY=xxxxxxxxxxxx
   ```

3. 在模块的 `build.gradle.kts` 中读取并注入：

   ```kotlin
   val localProps = java.util.Properties().apply {
       file("${rootProject.projectDir}/local.properties").inputStream().use { load(it) }
   }
   val mapsApiKey: String = localProps.getProperty("MAPS_API_KEY") ?: ""

   android {
       defaultConfig {
           manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
       }
   }
   ```

4. Manifest 中用占位符引用：

   ```xml
   <meta-data
       android:name="com.google.android.geo.API_KEY"
       android:value="${MAPS_API_KEY}" />
   ```

5. 同步更新 `local.properties.example`，说明该字段的含义（不要写真实值）。
6. 在云控制台对 Key 做**包名 + SHA1 双重限制**。
7. Release 构建后反编译验证 Key 未以明文出现在 `resources.arsc` / `AndroidManifest.xml` 中。

---

## 相关文件

- `.gitignore` 已忽略：`local.properties`、`*.jks`、`*.keystore`、`keystore.properties`、`google-services.json`
- `local.properties.example`：配置模板
