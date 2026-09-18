# FakeLocation - 安卓虚拟定位 App 产品需求文档

> 最后更新：2026-06-10
> 版本：0.2（补遗版，含 A~H 全部补充）

## 概述

一款基于 Android「开发者选项 - 模拟位置应用」机制的虚拟定位工具。用户可在地图上选点、搜索地点或规划路线，让设备向其他 App 报告一个"伪造"的位置。面向需要测试 LBS 应用、远程打卡、隐私保护等场景的普通用户与开发者。

**核心价值：** 无需 Root、操作直观、轨迹真实。

> **状态字段阅读说明**
>
> 本文档各条目标注的「状态：待定」是**规划期原始状态，未随开发进度更新**。请以
> [docs/implementation-status.md](./docs/implementation-status.md) 为准 —— 该文档给出了
> 逐条验收标准的实际完成情况（✅ 已完成 / ⚠️ 部分完成 / ❌ 未实现）以及对应代码位置。

---

## 1. 技术约束

| 维度 | 取值 |
|------|------|
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 29（Android 10） |
| 语言 | Kotlin 1.9.24 |
| UI | Jetpack Compose + Material 3（BOM 2024.06.00） |
| 构建 | AGP 8.5.2 + Gradle Convention Plugin + Version Catalog |
| JVM | 17 |
| DI | Hilt 2.51.1（kapt） |
| 异步 | Coroutines + Flow / StateFlow |
| 持久化 | DataStore Preferences（Room 依赖已备，待 v0.3 启用） |
| 网络 | `HttpURLConnection` + Nominatim（OSM 地址搜索）。Retrofit/OkHttp/Moshi 依赖已声明，**当前未使用** |
| 地图 | osmdroid 6.1.20（OpenStreetMap 瓦片，MIT 许可，**免 Key**） |
| 导航 | Navigation-Compose（依赖已声明，**当前为二元路由，未建 NavHost**） |
| 后台 | Foreground Service + WorkManager（**尚未实现**） |
| 国际化 | `values/`（中文默认）+ `values-en`。**无 `values-zh`**；且 UI 文案大量硬编码，国际化尚未真正生效 |
| 测试 | JUnit 4.13.2 + kotlin-test |
| 混淆 | R8（启用 minify + shrinkResources） |
| 包格式 | AAB（Android App Bundle） |

> 注 1：原计划使用 Google Maps（需要 API Key），v0.1 实施中切换为 osmdroid（OpenStreetMap），免 Key 且开源。后期如需更高视觉质量，可作为 feature flag 切回 Google Maps。
> 注 2：**本文档与代码实现的逐条对照见 [docs/implementation-status.md](./docs/implementation-status.md)。** 该文档列出了 17 处文档/实现冲突与 21 项代码问题，比本文档更贴近实际。

### 1.1 Android 版本适配（B）

**描述：** 针对 Android 12 / 13 / 14 的新权限与前台服务规范做适配。

**优先级：** P0
**状态：** 待定

**验收标准：**
- [ ] **Android 13+ (API 33)** 申请 `POST_NOTIFICATIONS` 运行时权限，未授权时降级为「无通知保活」
- [~] **Android 14+ (API 34)** 前台服务 `foregroundServiceType="location"` + manifest 声明 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>`
      → 权限**已声明**于 `app/src/main/AndroidManifest.xml`，但**前台服务尚未实现**（无任何 Service 类）
- [ ] **Android 14** 所有动态注册 `BroadcastReceiver` 显式声明 `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` 标志
- [ ] **Android 12+** 精确闹钟按需申请 `SCHEDULE_EXACT_ALARM`，否则使用 `setInexactRepeating` 降级
- ~~**Google Maps 暗色样式**：加载 `map_style_night.json`，跟随系统主题切换~~ → **已作废**，v0.1 已切换至 osmdroid，无 Google Maps SDK

### 1.2 Mock 核心引擎细节（C）

**描述：** Mock 位置引擎的底层行为规范。

**优先级：** P0
**状态：** 待定

**验收标准：**
- [x] **同时 mock `gps` + `network` 两个 provider**（部分打车/外卖 App 只读 `network`）
- [~] **注入频率可调**：默认 1 Hz，可选 0.5 / 1 / 2 / 5 Hz
      → `MockConfig.frequencyHz` 已实现并用 `sanitizedCopy()` 钳制到 [0.5, 5.0]，但**无 UI 入口**，实际恒为 1 Hz
- [~] **位置字段完整**：经纬度、accuracy(±5m)、altitude、bearing(0~360°)、speed(m/s) 全部正确填充
      → 经纬度 ✅、accuracy ✅（默认 5 m）、bearing ⚠️（有值但见下条）、**`altitude` 恒 0.0**、**`speed` 恒 0.0**
- [x] **模拟随机抖动 (jitter)**：在 ±3m 范围内做小幅度随机偏移，避免被识别为「静止点」异常
- [ ] **Bearing 派生**：根据相邻两段 polyline 方向角计算，而非固定值
      → **未实现**。单点模式下 `injectLoop()` 用 `bearing += random(-15°, +15°)` 随机漂移，并非派生自路径。该条实际属于路线模式（v0.4）
- [~] **注入失败处理**：
  - [x] `addTestProvider` 抛 `SecurityException` → 目前仅把原始异常 message 透传到 Snackbar，**文案不友好**，待优化为引导用户去开发者选项授权
  - [x] provider 已存在 → 先 `removeTestProvider` 再重新添加
  - [x] 并发竞争 → 内部 `Mutex` 加锁串行化 + `cancelAndJoin()` 保证停止时序
- [x] **恢复真实位置策略**：停止模拟后立即停止注入，不做「渐进回归」（避免误判为真实移动）

---

## 2. 模块：权限与引导（Onboarding）

### 2.1 首次启动引导

**描述：** App 首次启动以 3 步卡片引导用户完成必要设置。

**优先级：** P0
**状态：** 待定

**验收标准：**
- [ ] 卡片 1：介绍 App 用途，说明需要开发者选项
- [ ] 卡片 2：跳转到「开发者选项 - 选择模拟位置应用」设置页
- [ ] 卡片 3：申请 `ACCESS_FINE_LOCATION` 运行时权限
- [ ] 任意一步未完成时再次启动 App 能从中断处继续
- [ ] 全部完成后不再展示引导

### 2.2 权限与状态自检

**描述：** 主界面顶部状态条实时显示三项就绪状态：开发者选项模拟位置授权、目标 App 选择、运行时定位权限。

**优先级：** P0
**状态：** 待定

**验收标准：**
- [ ] 状态缺失时状态条变红并提示修复路径
- [ ] 点击状态项直接跳转到对应设置页
- [ ] 状态变更（用户在系统设置切换）能在返回 App 后 1s 内刷新

---

## 3. 模块：地图与单点定位（Single Point）

### 3.1 地图选点

**描述：** 在主地图页面长按地图任意位置，反选该点为 Mock 目标，蓝色 marker 标记。

**优先级：** P0
**状态：** 待定

**验收标准：**
- [ ] 长按位置放置 marker，并显示经纬度浮层
- [ ] 拖动 marker 可微调位置
- [ ] 地图可旋转/缩放/切换图层（普通/卫星）
- [ ] 显示当前位置与 mock 位置的连线

### 3.2 单点定位

**描述：** 点击「开始模拟」后将目标坐标通过 `LocationManager.setTestProviderLocation` 持续注入。

**优先级：** P0
**状态：** 待定

**验收标准：**
- [ ] 「开始」后通知栏出现常驻通知
- [ ] 第三方地图/打车/外卖等 App 立即显示目标位置
- [ ] 「停止」后位置恢复为真实位置
- [ ] 启动时校验 `isFromMockProvider` 返回 true

---

## 4. 模块：路线模拟（Route Simulation）

> ⚠️ **路线数据源已变更**：原计划的 Google Directions API 需 API Key，v0.4 起改用 **OSRM 公共 API**（免 Key）。
> 下文凡出现「Directions API」处，请按 OSRM 理解；polyline 编解码与偏差需按 OSRM 的 `geometry=polyline` 格式处理。
> 本节全部内容 **尚未实现**。

### 4.1 路线选择

**描述：** 用户设置起点和终点（可拖动 marker 或搜索），系统调用 Google Directions API 获取实际道路路径。

**优先级：** P0
**状态：** 待定

**验收标准：**
- [ ] 支持 1 个起点 + 1 个终点 + 最多 8 个途经点
- [ ] 路线以 polyline 在地图上高亮显示
- [ ] 显示总距离与预计时长

### 4.2 运动模式

**描述：** 选择运动模式后按对应速度在路径上线性插值出后续位置。

**优先级：** P0
**状态：** 待定

**验收标准：**
- [ ] 内置 4 种模式：步行 5 km/h、骑行 15 km/h、驾车 40 km/h、自定义
- [ ] 自定义支持 0.1 km/h 步进
- [ ] 模拟期间 marker 在地图上沿路径移动
- [ ] 支持暂停/恢复/停止/重置

### 4.3 后台运行

**描述：** App 退到后台时由 Foreground Service 持续发送位置，前台通知含暂停/停止按钮。

**优先级：** P1
**状态：** 待定

**验收标准：**
- [ ] 默认行为：App 在前台时模拟，App 进入后台 30s 后暂停
- [ ] 设置中可开启「后台保活」，开启后即使锁屏也持续模拟
- [ ] 前台服务通知符合 Android 14 前台服务类型规范（`location`）
- [ ] 用户关闭电池优化或加入白名单时有引导弹窗

### 4.4 路线插值算法（E）

**描述：** 在 Directions API 返回的 polyline 路径上按速度推进，输出符合真实道路的位置序列。

**优先级：** P0
**状态：** 待定

**验收标准：**
- [ ] **沿 polyline 步进**：以累计距离为进度，沿路点序列推进，而非 A→B 直线插值
- [ ] **Bearing 平滑**：相邻两段方向角取平均，避免急转
- [ ] **海拔/高程模拟**：Directions API 提供高程时填充 `altitude` 字段
- [ ] **GPX/KML 导入**：从文件导入自定义路线，覆盖默认 Directions 路线
- [ ] **GPX/KML 导出**：当前模拟的路线可导出为文件分享
- [ ] **定时启动**：指定时间自动开始模拟（如「7:50 启动到公司路线」）

---

## 5. 模块：搜索、收藏与历史

### 5.1 地点搜索

**描述：** 顶部搜索框调用 **Nominatim（OpenStreetMap 公共地理编码 API）** 实时联想（v0.2 实际实现，替代原计划的 Google Places Autocomplete）。

**优先级：** P1
**状态：** 待定

**验收标准：**
- [ ] 输入 2 字符后开始联想，250ms 防抖
- [ ] 点击结果跳到地图并放置 marker
- [ ] 网络异常时显示本地降级提示

### 5.2 收藏

**描述：** 用户可将当前选中位置加入收藏，名称可自定义或自动取搜索结果名称。

**优先级：** P1
**状态：** 待定

**验收标准：**
- [ ] 收藏列表展示名称、经纬度、最后使用时间
- [ ] 支持重命名、删除、点击即用
- [ ] 支持分组（自建分组）

### 5.3 历史记录

**描述：** 自动记录每次使用过的目标位置。

**优先级：** P1
**状态：** 待定

**验收标准：**
- [ ] 列表按时间倒序，最多 200 条
- [ ] 支持清空与单条删除
- [ ] 一键复用：点击直接开始模拟

---

## 6. 模块：设置（Settings）

**描述：** 用户偏好设置。

**优先级：** P2
**状态：** 待定

**子功能：**
- 主题：跟随系统 / 浅色 / 深色
- 语言：跟随系统 / 中文 / English
- 单位：公制 / 英制
- 默认速度与运动模式
- 启动时是否直接恢复上次状态
- 关于 / 反馈入口 / 隐私政策 / 开源协议

---

## 7. 模块：错误与边界处理（D）

**描述：** 覆盖所有异常路径与边界状态，确保 App 在网络异常、API 失败、权限丢失等场景下不会崩溃或卡死。

**优先级：** P0
**状态：** 待定

**验收标准：**

**网络异常：**
- [ ] Directions / Places API 超时（>10s）显示重试按钮与本地降级提示
- [ ] 配额耗尽（OVER_QUERY_LIMIT）提示用户稍后重试
- [ ] 完全离线时引导用户检查网络

**Mock 注入失败：**
- [ ] `addTestProvider` 抛 `SecurityException` → 红色 Toast 提示去开发者选项授权
- [ ] 权限被系统撤销时自动停止模拟并回到主界面

**空状态：**
- [ ] 首次进入收藏 / 历史为空时显示空插画与「去选个点试试」CTA
- [ ] 搜索无结果时显示「换个关键词试试」

**加载与进度：**
- [ ] API 请求期间显示进度条或骨架屏
- [ ] 模拟运行中显示当前速度、已移动距离、剩余距离

**提示规范：**
- [ ] 成功：Snackbar 短时（<2s）
- [ ] 警告：Snackbar 中时（3~4s）含「查看」动作
- [ ] 错误：Snackbar 长时（5s+）或 Dialog，需用户主动关闭

---

## 8. 模块：UX 增强（F）

**描述：** 提升日常使用效率的辅助交互。

**优先级：** P2（部分 P3）
**状态：** 待定

**子功能：**
- [ ] **App Shortcuts**（P2）：长按图标直接「回家 / 到公司」，调起收藏的首位
- [ ] **桌面 Widget**（P3）：一键开始 / 停止，显示当前 mock 状态
- [ ] **分享坐标**（P2）：调系统分享 Intent 发送 `geo:lat,lng` 链接
- [ ] **地图自动跟随**（P1）：模拟时相机自动居中且可关闭（避免抢视野）
- [ ] **使用教程页**（P2）：首次进入模拟页时弹出图文教程，可永久关闭
- [ ] **诊断页**（P1）：显示当前 mock provider 状态、注入频率、最后一次错误日志、可复制粘贴给反馈

---

## 9. 模块：测试（G）

**描述：** 建立可重复的测试体系，保证核心算法与关键流程稳定。

**优先级：** P0
**状态：** 待定

**验收标准：**

**单元测试（实际采用 JUnit 4.13.2 + kotlin-test；MockK 未引入）：**
- [ ] 路线插值算法：给定 polyline + 速度，输出位置序列与预期距离吻合（±1m）
      → **未实现**（路线模拟本身未开发）
- [x] 距离计算（Haversine）：与 Google 官方 SDK 误差 < 0.1%（`core-common/GeoUtilsTest`）
- [x] Bearing 计算：典型角度用例（正北 0°、正东 90°、转弯 135°）正确（同上）
- [x] 抖动算法：抖动范围在 ±3m 内（`core-mock/JitterGeneratorTest`）

**UI 测试（Compose Test）：**
- [ ] 主界面：长按地图 → marker 出现 → 按钮可点
- [ ] 收藏页：空状态、添加、删除、点击复用
- [ ] 引导页：跳过与完整流程

**集成测试：**
- [ ] Mock 注入 e2e：用真实第三方 App（如 Google Maps）验证 `isFromMockProvider == true`

**兼容性测试矩阵：**
- [ ] Pixel 6 / 8（Android 14）
- [ ] Samsung S23（One UI 6）
- [ ] Xiaomi 14（HyperOS）
- [ ] Huawei P60（HarmonyOS，需文档化限制）

---

## 10. 模块：工程与合规（A）

**描述：** Google Play 上架所需的技术与合规准备。

**优先级：** P0
**状态：** 待定

**验收标准：**

**API Key 保护（P0）—— 已改为「免 Key 架构」，本节验收项作废：**

> v0.1 起改用 osmdroid（OSM 瓦片，免 Key）+ Nominatim（地址搜索，免 Key）；路线规划计划 v0.4 改用 OSRM 公共 API。
> **本 App 不需要任何 API Key**，因此「Key 限制包名 + SHA1」「从 `local.properties` 注入」等验收项不再适用。
> 详细说明与「将来若引入需 Key 服务时的正确做法」见 [docs/api-keys.md](./docs/api-keys.md)。
>
> ⚠️ 转为必须遵守的 OSM 使用政策：
> - [x] 请求时设置可识别的 `User-Agent`（`HomeViewModel.nominatimSearch()` 已设置）
> - [ ] 界面上展示「© OpenStreetMap contributors」署名（**当前缺失，上架前必补**）
> - [ ] 地址搜索补防抖，避免高频请求 Nominatim（**当前缺失，同时是 §5.1 验收项**）

**ProGuard / R8（P0）：**
- [ ] 启用 `minifyEnabled` + `shrinkResources`
- [ ] Hilt / osmdroid / kotlinx-serialization（若引入）keep 规则。注：Retrofit 与 Room 当前**未被使用**，待实际引入后再补规则
- [ ] 第三方 SDK（Google Maps、Crashlytics）官方 keep 规则
- [ ] Release 构建反编译验证无敏感信息

**崩溃与日志上报（P1）：**
- [ ] 接入 Firebase Crashlytics 或 Sentry
- [ ] 用户可在设置页一键关闭上报
- [ ] 严禁上报位置等敏感字段

**签名与发布（P1）：**
- [ ] Play App Signing 启用
- [ ] AAB 构建脚本（./gradlew bundleRelease）
- [ ] 内部测试轨道 → 封闭测试 → 公开测试 → 生产

**上架合规（P0）：**
- [ ] Data Safety 表填写完整（位置数据本地存储、不收集）
- [ ] 隐私政策 URL（自建静态页）
- [ ] **隐私政策必须披露**：地址搜索会把用户输入的关键词发送至 `nominatim.openstreetmap.org`；地图会向 OSM 瓦片服务器发起请求（这两项属于事实上的网络传输，未披露会造成合规风险）
- [ ] 应用描述明确用途，避免「打卡」「绕过定位」等敏感词
- [ ] 目标用户年龄分级
- [ ] 类别：工具（Tools），非游戏

---

## 11. 非功能需求

| 维度 | 要求 |
|------|------|
| 启动时间 | 冷启动 < 1.5 s（Pixel 6 参考） |
| 内存占用 | 静止态 < 150 MB |
| 电量 | 后台模拟 1 小时耗电 < 8%（Pixel 6 参考） |
| 兼容性 | 通过 Google Play Pre-launch 报告无 P0 Crash |
| 隐私 | 全部位置数据本地存储，不上传服务端 |
| 反检测 | 文档化说明本 App 不保证能欺骗具备 `isFromMockProvider` 校验的应用 |

---

## 12. 项目结构（含多模块拆分 H）

> 下表为**实际**结构（已与 `settings.gradle.kts` 核对，2026-09-18）。✅ = 已实现，⬜ = 空壳占位。

```
fake-location/                    # 仓库根即项目根
├── app/                          # :app                     ✅ 入口 / 根路由
├── core/
│   ├── core-common/              # :core:core-common        ✅ LatLng / GeoUtils / MovementMode
│   ├── core-mock/                # :core:core-mock          ✅ Mock 引擎（纯 Android library，可独立单测）
│   └── core-ui/                  # :core:core-ui            ✅ Compose 主题与通用组件
├── data/
│   ├── data-local/               # :data:data-local         ⬜ Room + DataStore（占位）
│   ├── data-remote/              # :data:data-remote        ⬜ 网络数据源（占位）
│   └── data-repository/          # :data:data-repository    ⬜ Repository 聚合（占位）
├── feature/
│   ├── feature-onboarding/       # :feature:feature-onboarding  ✅ 引导（3 页）
│   ├── feature-home/             # :feature:feature-home        ✅ 地图主页面 + 单点定位
│   ├── feature-search/           # :feature:feature-search      ⬜ 占位
│   ├── feature-favorites/        # :feature:feature-favorites   ⬜ 占位
│   ├── feature-history/          # :feature:feature-history     ⬜ 占位
│   ├── feature-route/            # :feature:feature-route       ⬜ 占位
│   ├── feature-settings/         # :feature:feature-settings    ⬜ 占位
│   └── feature-diagnostics/      # :feature:feature-diagnostics ⬜ 占位
├── build-logic/                  # :build-logic              6 个 Convention Plugin
├── docs/                         # 工程文档（architecture / implementation-status / api-keys / release-checklist）
├── gradle/libs.versions.toml     # 依赖版本唯一来源（Version Catalog）
├── REQUIREMENTS.md
└── README.md
```

**与规划期方案的差异（已修正）：**

- ~~`:core:core-location`~~ → **不存在**，位置相关工具方法目前放在 `:core:core-common` 的 `GeoUtils`
- 实际 core 模块为 3 个：`core-common` / `core-mock` / `core-ui`
- 注：v0.2 起 `feature-search` 的功能（地址搜索）**临时实现在 `feature-home` 内**，尚未拆到 `feature-search` 模块

**每个 module 结构：**
```
<module>/
├── src/main/kotlin/com/fakelocation/<module-path>/
│   ├── di/                # 暴露给其他 module 的 Hilt Module
│   ├── <业务代码>
│   └── api/               # 对外的 public interface
├── src/test/              # 单元测试
└── build.gradle.kts
```

> 注：实际代码使用 `src/main/kotlin/`（非 `src/main/java/`），包名与模块路径一一对应。

**多模块优势：**
- Mock 引擎可独立单测，不依赖 Android 框架
- 编译并行化，构建速度提升
- 业务模块按需打包，可后续出「Wear OS 精简版」等变体

> ⚠️ 当前 `app/build.gradle.kts` 仅依赖 `:core:core-ui`、`:feature:feature-onboarding`、`:feature:feature-home`。
> `:data:*` 与 6 个占位 feature 模块**未被接线**，新增功能时需同步补依赖，否则代码不会进入 APK。

---

## 13. 迭代里程碑

| 版本 | 内容 | 状态 |
|------|------|------|
| v0.1 | 项目骨架（含多模块 H）+ 单点定位 MVP | ✅ **已完成**（debug/release APK 均已产出） |
| v0.2 | 引导页 + 权限自检 + Android 14 适配 | 🟡 **基本完成**：引导页 ✅、权限自检 ✅、地址搜索（计划外新增）✅；Android 14 前台服务类型仅声明权限，**Service 未实现**；`versionName = 0.2.0` |
| v0.3 | 搜索 + 收藏 + 历史 + 错误处理 D | ⬜ 未开始（模块已占位；地址搜索已提前在 feature-home 内落地） |
| v0.4 | 路线模拟（**OSRM** + 插值 E + 算法单测） | ⬜ 未开始 |
| v0.5 | Foreground Service + 后台保活 + Android 14 FGS 类型 | ⬜ 未开始 |
| v0.6 | 设置 + 国际化 + 深色模式 + UX 增强 F | ⬜ 未开始 |
| v0.7 | 诊断页 + Crashlytics + 工程合规 A + ProGuard | ⬜ 未开始 |
| v0.8 | 兼容性测试矩阵 + UI/集成测试 G | ⬜ 未开始 |
| v1.0 | Play Store 提审 | ⬜ 未开始（当前 release 用 debug 签名，**不可上架**） |

> 上架前的阻塞项清单见 [docs/release-checklist.md](./docs/release-checklist.md) 第 0 节。

---

## 14. 风险与限制

1. **Android 6+ 强制要求用户在「开发者选项」手动指定本 App 为模拟位置应用**，无法绕过。
2. 部分 App 通过 `Location.isFromMockProvider` 或服务端 IP/基站交叉校验识别虚拟定位，本 App **不保证**能欺骗这些 App。
3. 国内厂商（华为、小米、OPPO 等）部分 ROM 限制 mock provider，需文档化说明。
4. Android 14 前台服务类型必须声明 `location`，否则会被系统拒绝启动。
5. Google Play 政策对模拟位置类 App 有额外审核，需在 Store 描述中明确用途并避免误导。
6. ~~**首次启动耗时**取决于 Google Maps SDK 加载~~ → 已切 osmdroid，无 SDK 加载开销；改为**首次地图渲染耗时取决于 OSM 瓦片下载**，弱网/无网地区需 loading 占位（当前 `OsmMapContent` 无加载态提示）。
7. **jitter 算法**仅在视觉层面让轨迹更真实，对「设备指纹 + 行为画像」级别的反作弊无效。
8. **依赖 OSM 公共基础设施**：瓦片与 Nominatim 均为志愿者运维。当前 Nominatim 无防抖、且未展示 OSM 署名，**同时构成体验风险与合规风险**。
9. **无版本控制**：仓库尚未 `git init`，任何改动不可回滚（见 [docs/implementation-status.md](./docs/implementation-status.md) P0 项 1）。

---

## 变更日志

| 日期 | 变更内容 | 原因 |
|------|---------|------|
| 2026-06-10 | 新建文档，完成 v0.1 规划 | 用户提出项目规划需求 |
| 2026-06-10 | v0.2：补遗 A~H<br>- 1.1 Android 12/13/14 适配（B）<br>- 1.2 Mock 引擎技术细节（C）<br>- 4.4 路线插值算法（E）<br>- 7 错误与边界处理模块（D）<br>- 8 UX 增强模块（F）<br>- 9 测试模块（G）<br>- 10 工程与合规模块（A）<br>- 12 项目结构升级为多模块（H）<br>- 13 里程碑细化到 9 步 | 用户复审发现遗漏 |
| 2026-06-10 | v0.1 实施完成：多模块骨架 + 单点定位 MVP | — |
| 2026-06-10 | v0.1 实施中：地图 SDK Google Maps → **osmdroid** | 避免 Google Maps API Key 申请门槛 |
| 2026-06-10 | v0.2 实施：引导页 + 权限自检；地址搜索改用 **Nominatim**（计划外） | 国内 ROM 系统 Geocoder 静默返回空列表 |
| **2026-09-18** | **文档规整：与代码实现全面对齐**<br>- §1 技术约束表更新（targetSdk 34→36、补 compileSdk、网络改 Nominatim、国际化修正、补测试框架行）<br>- §1.1 删除已作废的 Google Maps 暗色样式项；前台服务项标注「权限已声明、Service 未实现」<br>- §1.2 验收项标注实际完成度（altitude/speed 恒 0、bearing 非派生、频率无 UI 入口）<br>- §9 测试框架 JUnit5+MockK → JUnit 4 + kotlin-test，标注已完成用例<br>- §10 删除与「免 Key 架构」矛盾的 API Key 验收项，转为 OSM 使用政策项；隐私政策补 Nominatim 披露要求<br>- §12 项目结构改为实际结构（删除不存在的 core-location，修正源码目录与根路径，标注各模块实现状态）<br>- §13 里程碑状态更新为实际进度<br>- §14 风险项更新（Google Maps SDK → OSM 瓦片；新增 OSM 依赖风险、无版本控制风险）<br>- 新增 `docs/` 目录：architecture.md、implementation-status.md、api-keys.md、release-checklist.md<br>- 重写 `README.md` | 文档与实现脱节，存在 17 处冲突；为后续新功能开发建立准确基线 |
