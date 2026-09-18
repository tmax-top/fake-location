# 外部服务依赖与国内网络可达性

> 最后更新：2026-09-18
> 关联：`docs/api-keys.md`（为什么免 Key）、`docs/implementation-status.md`（实现状态）

本 App 依赖两组**免 Key** 的 OSM 外部服务。它们在国内网络下的可达性直接决定核心功能是否可用 —— 这份文档记录了实测结论与代码里的应对策略。

---

## 1. 被污染的两个域名

`*.openstreetmap.org` 在国内会被 **DNS 污染**（解析到无关且不可达的 IP）。

**2026-09-18 实测**（Redmi 24122RKC7C / Android 17，Wi-Fi「A_5G」→ 小米路由器 `192.168.31.1`）：

| 域名 | 解析结果 | TCP 443 |
|------|---------|---------|
| `tile.openstreetmap.org` | `156.233.67.243` | ❌ ConnectException |
| `nominatim.openstreetmap.org` | `74.86.142.55` | ❌ 连接超时 |

对照实验（同一台手机）：

| 解析服务器 | `nominatim` 应答 | 判定 |
|-----------|-----------------|------|
| `192.168.31.1`（路由器） | `74.86.142.55` | ❌ 不可达 |
| `114.114.114.114` | `146.75.45.91` | ✅ **正确**（Fastly），实测可连 |
| `223.5.5.5`（阿里） | `31.13.73.169` | ❌ **Facebook 的 IP 段**，典型污染特征 |
| `8.8.8.8`（Google） | `179.60.193.9` | ❌ **Facebook 的 IP 段** |

**关键结论：网络链路本身是通的**（直连正确的 `146.75.45.91:443` 返回 TCP-OK），
问题**纯粹在 DNS 解析**。因此「换一个没被污染的域名」即可修复。

App 侧表现（修复前的日志）：

```
OsmDroid: IOException downloading MapTile: /12/3429/1677 :
  java.net.ConnectException: Failed to connect to tile.openstreetmap.org/156.233.67.243:443
HomeViewModel: 搜索失败：failed to connect to nominatim.openstreetmap.org/199.16.156.39 (port 443)
  from /192.168.31.85 (port 40030) after 5000ms
```

现象特征：**地图只有缓存过的区域能显示，拖到没缓存的地方就是空白** —— 容易被误判为「地图渲染 bug」。

---

## 2. 应对策略（已实装）

### 2.1 地图瓦片：候选源 + 可达性探测

`feature/feature-home/.../map/OsmTileSources.kt`

按优先级探测，取第一个可达的源：

| 顺序 | 源 | URL 前缀 | 实测 |
|------|----|---------|------|
| 1 | OSM France（HOT 样式） | `https://a.tile.openstreetmap.fr/hot/` | ✅ 200 |
| 2 | OSM Germany（标准样式） | `https://a.tile.openstreetmap.de/` | ✅ 200 |
| 3 | OSM 官方（兜底） | `tile.openstreetmap.org` | ❌（挂代理时可用） |

- 探测方式：对固定瓦片 `z=12/x=3429/y=1677` 发 `Range: bytes=0-0` 的 GET，4s 超时，判定 2xx
- 结果缓存在进程内（`@Volatile` + 协程 `Mutex` 双重检查），整进程只探测一次
- 首帧用默认候选渲染，探测完成后 `mapView.setTileSource()` 切换，不阻塞 UI

> ⚠️ 三个候选**全部是 OSM 数据、ODbL 许可、WGS-84 坐标系**。
> 切换源不会有任何坐标偏移。**切勿**为了国内速度换成高德/腾讯瓦片 ——
> 那是 GCJ-02 坐标系，会让「地图上选的点」与「实际注入的坐标」相差 50~500 m。

### 2.2 地址搜索：Photon 主源 + Nominatim 兜底

`feature/feature-home/.../HomeViewModel.kt`

| 顺序 | 服务 | 端点 | 实测 |
|------|------|------|------|
| 1 | **Photon**（Komoot，基于 OSM） | `https://photon.komoot.io/api/` | ✅ 200，中文结果质量好 |
| 2 | Nominatim（兜底） | `nominatim.openstreetmap.org` | ❌（国外网络 / 代理时可用） |

- Photon 返回 GeoJSON `FeatureCollection`，注意 **`geometry.coordinates` 是 `[经度, 纬度]`**
- **不要传 `lang=zh`** —— Photon 只支持 `default/de/en/fr`，传 `zh` 直接返回 HTTP 400。
  不带 `lang` 时返回 OSM 原始名称，中国区域数据本身就是中文
- **降级只在 Photon 请求失败（抛异常）时触发，返回 0 条不降级** ——
  「查得到但没匹配」是正常业务结果，不是网络故障。若此时降级到国内不可达的 Nominatim，
  用户要白等 8s 连接超时 + 10s 读超时（实测输入「上海东镇高」踩到过这个坑）
- 实际验证：`q=shanghai` → 「上海站 · 上海火车站南广场 · 天目西路街道 · 静安区 · 上海市 · 中国」

---

## 3. 排查同类问题的方法

设备连上 adb 后：

```bash
# 1. 看域名解析到了什么（与正确值对比）
adb shell ping -c 1 nominatim.openstreetmap.org

# 2. 验证是不是「DNS 错、链路对」：直连已知正确的 IP
adb shell "nc -w 6 -z 146.75.45.91 443 && echo TCP-OK || echo TCP-FAIL"

# 3. 对比不同 DNS 的应答（在电脑上执行，把 <ns> 换成路由器/DNS 地址）
dig +short @<ns> nominatim.openstreetmap.org

# 4. 看 App 实际报错
adb logcat -d | grep -E "OsmTileSources|HomeViewModel|IOException downloading MapTile"
```

**判据**：如果 `ping` 出来的 IP「直连不通」而「直连正确 IP 通」，就是 DNS 问题，不是网络封锁。

---

## 4. 现状与残留风险

| 项 | 说明 |
|----|------|
| 地图瓦片 | ✅ 已有 3 源降级。但降级发生在**进程启动时一次**，运行中若源被污染不会自动切换 |
| 地址搜索 | ✅ 已有 2 源降级。Photon 与 Nominatim 都在国外，理论上都可能被污染 |
| OSM 署名 | ✅ 已加 `CopyrightOverlay`（ODbL 合规要求） |
| 公共服务依赖 | ⚠️ Photon / OSM France / OSM Germany 均为**志愿者运维的免费服务**，有非正式的使用量期待。**长期方案是自建瓦片服务或引入商业地图 API**（见 `docs/api-keys.md`） |
| 坐标系 | ✅ 全部 WGS-84，与注入坐标一致 |
| 用户侧兜底 | 若某网络下三个瓦片源全不可达，App 会降级用第一个源并持续重试；搜索会给出「地址服务不可达，请检查网络」提示 |

### 尚未做的加固（可选）

1. **运行中重新探测**：监听瓦片连续失败次数，超过阈值则重跑探测（当前只在启动时探测一次）
2. **设置页数据源开关**：允许用户手动指定瓦片源 / 填自备高德 Key
3. **坐标转换层**：若将来真要用高德底图，必须先实现 WGS-84 ↔ GCJ-02 转换
