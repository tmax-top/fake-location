# 实现现状与需求对照

> 最后更新：2026-09-18
> 代码基线：`versionName = 0.2.0`
> 对照对象：[REQUIREMENTS.md](../REQUIREMENTS.md)

---

## 0. 最近修复（2026-09-18）

### 地图空白 + 搜索不可用 —— 根因是 DNS 污染

**现象**：地图拖到新区域就是空白（只有缓存区域能看）；地址搜索报
`failed to connect to nominatim.openstreetmap.org/199.16.156.39 (port 443) ... after 5000ms`。

**根因**：`*.openstreetmap.org` 域名在国内被 DNS 污染，被解析到不可达 IP。
**网络链路本身正常** —— 直连正确的 `146.75.45.91:443` 返回 TCP-OK，问题纯粹在解析。

**修复**（完整实测记录见 [network-reachability.md](./network-reachability.md)）：

| # | 改动 | 文件 |
|---|------|------|
| 1 | 地图瓦片改为「候选源 + 可达性探测」，自动切到 OSM France / Germany / 官方 | `feature-home/.../map/OsmTileSources.kt`（新增）<br>`feature-home/.../HomeRoute.kt` |
| 2 | 地址搜索改为 Photon 主源 + Nominatim 兜底 | `feature-home/.../HomeViewModel.kt` |
| 3 | 补上 250ms 搜索防抖（原为每键入即请求） | 同上 |
| 4 | 错误提示改为可操作文案（原文是原始异常字符串） | 同上 |
| 5 | 补 OSM 署名 `CopyrightOverlay`（ODbL 合规要求，此前缺失） | `feature-home/.../HomeRoute.kt` |
| 6 | 超时 5s → connect 8s / read 10s | `feature-home/.../HomeViewModel.kt` |
| 7 | 修正降级语义：Photon 返回 0 条**不**降级，仅请求失败才降级（避免白等 18s 超时） | 同上 |

**真机验证结果**（Redmi 24122RKC7C / Android 17）：

```
OsmTileSources: 瓦片源探测成功: OSMFranceHOT (https://a.tile.openstreetmap.fr/hot/12/3429/1677.png)
IOException downloading MapTile 失败数 = 0     （修复前：持续 ConnectException）
Photon request: https://photon.komoot.io/api/?q=shanghai&limit=6
降级 Nominatim 次数 = 0                        （主源直接命中）
```

搜索返回 6 条中文结果，显示名拼接正常，例：
`上海站 · 上海火车站南广场 · 天目西路街道 · 静安区 · 上海市 · 中国`

> 本次修复**未引入任何 API Key**，仍保持「免 Key」架构；三者均为 OSM 数据、ODbL 许可、WGS-84 坐标系。

---

## 1. 需求 vs 实现：冲突项（需要修正文档或补实现）

| # | 位置 | REQUIREMENTS.md 写的 | 代码实际 | 处理建议 |
|---|------|---------------------|---------|---------|
| 1 | §1 技术约束 | `targetSdk 34`（Android 14） | **36**（`KotlinConfig.kt` / `AndroidApplicationConventionPlugin.kt`） | 改文档 |
| 2 | §1 技术约束 | 未提 `compileSdk` | **36** | 补文档 |
| 3 | §1 技术约束 | 网络：`Retrofit + OkHttp`（Google Places / Directions） | **`HttpURLConnection` + Nominatim**（OSM）。Retrofit/OkHttp/Moshi 依赖已声明但**零使用** | 改文档；并决定是否清理未用依赖 |
| 4 | §1 技术约束 | 国际化 `values-zh / values-en` 双语 | 只有 `values/`（中文默认）+ `values-en`；**无 `values-zh`** | 改文档 |
| 5 | §1.1 验收项 | 「Google Maps 暗色样式：加载 `map_style_night.json`」 | 已切 osmdroid，该条**永久失效** | 删除该条 |
| 6 | §1.1 验收项 | Android 14 `foregroundServiceType="location"` | Manifest 已声明 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION` 权限，但**没有任何 Service 实现**（无前台服务） | 标注「权限已备，服务未实现」 |
| 7 | §9 测试 | `JUnit5 + MockK` | **JUnit 4.13.2 + kotlin-test**，无 MockK | 改文档 |
| 8 | §9 测试 | 单测覆盖：路线插值、Haversine、Bearing、抖动 | 只有 `GeoUtilsTest`（Haversine/Bearing）、`JitterGeneratorTest`（抖动）。**路线插值不存在** | 路线插值随 v0.4 一起做 |
| 9 | §10 工程合规 | 「API Key 保护」标题下同时写「v0.1 已废弃改用 osmdroid」**又**保留「Maps/Places/Directions Key 限制包名 + SHA1」验收项 | 无 Key，实际无需保护 | 删除矛盾验收项，改为「免 Key 架构说明」并链接 `docs/api-keys.md` |
| 10 | §10 工程合规 | 引用 `docs/api-keys.md`、`docs/release-checklist.md` | 文档此前**不存在** | ✅ 已补齐 |
| 11 | §12 项目结构 | 含 `:core:core-location` | 该模块**不存在**（`settings.gradle.kts` 无此 include） | 删除该行 |
| 12 | §12 项目结构 | 根目录写作 `fakelocation/` | 仓库根即项目根 | 改文档 |
| 13 | §12 项目结构 | 含 `docs/`（api-keys.md、release-checklist.md） | 原不存在 | ✅ 已补齐 |
| 14 | §13 里程碑 | v0.1 / v0.2 状态均为「待定」 | v0.1 ✅ 已完成（APK 已产出）、v0.2 已完成引导页部分 | 更新为「已完成」/「部分完成」 |
| 15 | §14 风险 | 「首次启动耗时取决于 **Google Maps SDK** 加载」 | 已切 osmdroid，该风险不再适用（改为瓦片下载耗时） | 改文档 |
| 16 | §14 风险 | 「jitter 对设备指纹 + 行为画像级反作弊无效」 | 与实现一致 ✅ | 保留 |
| 17 | 变更日志 | 存在空日期行、A~H 补遗挂在无日期下 | 格式混乱 | 已重排 |

---

## 2. 需求 vs 实现：已完成项（可打勾）

### §2 权限与引导 —— ✅ 已完成

| 验收项 | 状态 | 说明 |
|-------|------|------|
| 卡片 1：介绍用途 + 说明需要开发者选项 | ✅ | `OnboardingViewModel.pages[0]` |
| 卡片 2：跳转「选择模拟位置应用」设置页 | ✅ | `Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS`，跳转失败有 Snackbar 提示 |
| 卡片 3：申请 `ACCESS_FINE_LOCATION` | ✅ | `RequestMultiplePermissions`，含 COARSE |
| 任意一步未完成可从中断处继续 | ⚠️ 部分 | 页面会重新走一遍，但**授权状态是实时探测的**，已完成项会显示 ✅ 并允许直接下一步 |
| 全部完成后不再展示引导 | ✅ | DataStore `onboarding_prefs.completed` |

### §2.2 权限与状态自检 —— ✅ 已完成

| 验收项 | 状态 | 说明 |
|-------|------|------|
| 状态缺失时变红并提示修复路径 | ✅ | `HomeRoute.StatusBanner` |
| 点击状态项跳转对应设置页 | ❌ | 横幅**只显示文字，不可点击** |
| 状态变更后返回 App 1s 内刷新 | ✅ | `LifecycleEventEffect(ON_RESUME)` |

### §3 地图与单点定位 —— ✅ 基本完成

> 🔧 2026-09-18：瓦片源已从固定的 `tile.openstreetmap.org`（国内被 DNS 污染）
> 改为「候选源 + 可达性探测」，默认切到 OSM France HOT。详见本文档第 0 节。

| 验收项 | 状态 | 说明 |
|-------|------|------|
| 长按放置 marker + 经纬度浮层 | ⚠️ 部分 | marker 有；经纬度在**底部栏与状态横幅**显示，非地图浮层 |
| 拖动 marker 微调 | ❌ | marker 未设 `isDraggable` |
| 地图旋转 / 缩放 / 图层切换（普通/卫星） | ⚠️ 部分 | 缩放 ✅、多点触控 ✅、**瓦片源自动降级 ✅**；**旋转关闭**、**无图层切换** |
| 显示当前位置与 mock 位置连线 | ❌ | 未实现（有 `MyLocation` FAB 但无连线） |
| 开始后通知栏常驻通知 | ❌ | 无前台服务 |
| 第三方 App 立即显示目标位置 | ✅ | 双 provider 注入 |
| 停止后恢复真实位置 | ✅ | `removeTestProvider` |
| 启动时校验 `isFromMockProvider` 返回 true | ❌ | 未实现自校验 |
| OSM 署名（ODbL 合规） | ✅ | 2026-09-18 补 `CopyrightOverlay`（地图页；「关于」页仍待补） |

### §1.2 Mock 核心引擎细节 —— ⚠️ 大部分完成

| 验收项 | 状态 | 代码位置 |
|-------|------|---------|
| 同时 mock gps + network | ✅ | `MockLocationEngineImpl.setupProviders()` |
| 注入频率可调：默认 1 Hz，可选 0.5/1/2/5 Hz | ⚠️ 部分 | `MockConfig.frequencyHz` 支持且 `sanitizedCopy` 钳制到 [0.5, 5.0]，但**没有 UI 可改**，恒为 1 Hz |
| 位置字段完整（accuracy/altitude/bearing/speed） | ⚠️ 部分 | accuracy ✅ 5m；bearing 有值（但见下方问题）；**`altitude` 恒 0.0**、**`speed` 恒 0.0** |
| 模拟随机抖动 ±3m | ✅ | `JitterGenerator`，默认 3.0 m |
| Bearing 派生自相邻 polyline 方向角 | ❌ | 单点模式下 `injectLoop` 用 `bearing += random(-15°, +15°)` 随机漂移，**并非派生自路径**。该验收项属路线模式（v0.4） |
| 注入失败处理：SecurityException 提示 | ⚠️ 部分 | 引擎返回 `Result.failure`，`HomeViewModel` 落 `_error` → Snackbar；文案是原始异常 message，**非友好提示** |
| provider 已存在 → 先 remove 再 add | ✅ | `setupProviders()` 开头清理 |
| 并发竞争 → 加锁串行化 | ✅ | `Mutex` + `cancelAndJoin` |
| 停止后立即停止注入，不做渐进回归 | ✅ | 直接 `removeTestProvider` |

### §5.1 地点搜索 —— ✅ 已完成（实现在 feature-home 内，未拆到 feature-search）

| 验收项 | 状态 | 说明 |
|-------|------|------|
| 输入 2 字符后开始联想，250ms 防抖 | ✅ | `MIN_QUERY_LENGTH = 2` + `SEARCH_DEBOUNCE_MS = 250`，新一轮输入先取消上一轮任务 |
| 点击结果跳到地图并放置 marker | ✅ | `pickSearchResult` |
| 网络异常时显示降级提示 | ✅ | 双源降级 Photon → Nominatim；两源均不可达时提示「地址服务不可达，请检查网络后重试」 |
| 实现位置 | ⚠️ | 仍写在 `feature-home/HomeViewModel`，`feature-search` 模块仍是空占位（待 v0.3 迁移） |

### 未开始

- §4 路线模拟（全部）
- §4.3 后台运行 / 前台服务
- §5.2 收藏、§5.3 历史
- §6 设置
- §7 错误与边界处理（部分已隐含实现）
- §8 UX 增强（App Shortcuts / Widget / 分享坐标 / 相机跟随 / 教程页 / 诊断页）
- §10 ProGuard 规则完善、崩溃上报、Play 签名与上架合规
- §11 非功能需求（未做实测）

---

## 3. 代码层面已知问题清单

按严重度排序，供后续功能开发时一并处理。

### P0

| # | 问题 | 位置 |
|---|------|------|
| 1 | **未初始化 Git**，无版本控制，改动不可回滚 | 仓库根（无 `.git`） |
| 2 | Release 用 `debug.keystore` 签名，产物不可上架 | `app/build.gradle.kts` release block |

### P1

| # | 问题 | 位置 | 影响 |
|---|------|------|------|
| 3 | **UI 文案硬编码中文**，`strings.xml` 中已定义的 key 未被使用 | `HomeRoute.kt`（"搜索地址"、"当前位置"、"开始模拟"、"停止模拟"、"请先在地图上选点"、"Mock 目标"）、`StatusBanner` 全部文案、`OnboardingRoute.kt`、`OnboardingViewModel.kt`（3 页全部文案） | 切换英文后引导页与主页面仍是中文，国际化形同虚设 |
| 4 | **无 NavHost**，只有 `needsOnboarding` 二元 switch | `MainActivity.AppRoot()` | 新增多页面功能前必须先建导航图 |
| 5 | `:data:*` 与 6 个 feature 占位模块**未被 `:app` 依赖** | `app/build.gradle.kts` | 直接写业务代码不会进 APK |
| ~~6~~ | ~~搜索无防抖，连续键入会打多次 Nominatim~~ | — | ✅ **已修复**（2026-09-18）：加了 250ms 防抖，主源改为 Photon |
| 7 | 无任何静态检查（ktlint / detekt）与 CI | 全仓库 | 规范靠人工 |

### P2

| # | 问题 | 位置 | 说明 |
|---|------|------|------|
| 8 | 单点模式 `altitude` 恒 0.0、`speed` 恒 0.0 | `MockLocationEngineImpl.pushLocation()` | 与 §1.2「字段完整」验收项不符 |
| 9 | 单点模式 bearing 每 tick 随机 ±15° 漂移，而 speed=0 | 同上的 `injectLoop()` | 逻辑矛盾：静止却方向漂移。建议单点模式 bearing 固定或不做抖动 |
| 10 | `combine` 7 参 + `values[i] as T` 强转 | `HomeViewModel.uiState` | 类型安全靠约定，新增状态字段易错位。建议改 `combine` 嵌套或 `combine(vararg)` 显式解构 |
| 11 | `while (scope.isActive)` 用外部 scope 而非协程自身 `isActive` | `MockLocationEngineImpl.injectLoop()` | 取消依赖 `delay` 抛 `CancellationException`，能工作但语义不严谨 |
| 12 | 死代码 | `GeoUtils.UNUSED`、`JitterGenerator.deg2rad()`、`JitterGenerator.bearingShim()` | 均为 `@Suppress("unused")` 残留 |
| 13 | `MovementMode.CUSTOM.defaultSpeedKmh = 10.0` 与 `MockConfig.customSpeedMps` 默认值（= WALK 的 5 km/h）不一致 | `MovementMode.kt` / `MockConfig.kt` | 默认值语义分歧 |
| 14 | `:app` 未依赖 `:data:*`，但 `:data:*` 已声明 Room/Retrofit 依赖 | 各 build.gradle.kts | 未使用的依赖会拖慢构建 |
| 15 | 测试仅 2 个纯函数单测 | `core-common`、`core-mock` | 无 ViewModel 测试、无 Compose UI 测试、无集成测试 |
| 16 | `StatusBanner` 不可点击 | `HomeRoute.kt` | 与 §2.2 验收项「点击跳转设置页」不符 |
| 17 | 地图无法旋转、无图层切换、无位置连线、marker 不可拖动 | `OsmMapContent` | 与 §3.1 验收项不符 |

### P3

| # | 问题 | 说明 |
|---|------|------|
| 18 | 厂商 ROM 兼容性矩阵未验证（MIUI / EMUI / HarmonyOS / ColorOS） | 需真机测试 |
| 19 | `AccessMockLocation` 权限声明的 ROM 兼容性假设未验证 | Manifest 注释中说明是「国内 ROM 过滤条件」，属假设 |
| 20 | 无 Crash 上报（Crashlytics / Sentry） | §10 P1 项 |
| 21 | `.gitignore` 已忽略 `/build`、`/app/build`，但 `build-logic/convention/build/` 未被忽略（模式 `/*/build/` 不覆盖二级） | 建议补 `/build-logic/**/build/` |

---

## 4. 建议的下一步

### 立即（不依赖新功能，5 分钟）

```bash
cd /Users/mk/mk/git/fake-location
git init
git add .
git commit -m "chore: 初始提交（v0.2.0 基线）"
```

### 开发新功能前建议先补

1. **导航骨架**（问题 #4）—— 否则任何多页面功能都要先返工
2. **`:app` 依赖接线**（问题 #5）—— 把要用的 feature / data 模块加进 `app/build.gradle.kts`
3. **文案抽取**（问题 #3）—— 新增页面若继续硬编码，国际化债务会持续累积
4. **`.gitignore` 补 `build-logic` 产物**（问题 #21）—— 避免首次提交带入编译产物

### 可随功能一起清

问题 #8/#9（引擎字段）、#10（combine 重构）、#12（死代码）、#13（默认值统一）
