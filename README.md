# FakeLocation

基于 Android「开发者选项 - 选择模拟位置应用」机制的**虚拟定位工具**。免 Root、免 API Key。

用户在地图上选点（或搜索地址），App 通过 `LocationManager.setTestProviderLocation` 向系统的 `gps` + `network` 两个 provider 持续注入伪造坐标，其他 App（地图、打车、外卖、社交等）随即读到指定位置；停止后立即恢复真实位置。

> ⚠️ 仅用于 LBS 应用测试、隐私保护等合法场景。见 [风险与限制](#风险与限制)。

---

## 当前状态

| 版本 | 内容 | 状态 |
|------|------|------|
| v0.1 | 多模块骨架 + 单点定位 MVP | ✅ 已完成 |
| v0.2 | 首次启动引导（3 页）+ 权限/授权自检 + 地址搜索 | ✅ 已完成（`versionName = 0.2.0`） |
| v0.3 | 搜索 / 收藏 / 历史 + 错误处理 | ⬜ 未开始（模块已占位） |
| v0.4 | 路线模拟（polyline 插值） | ⬜ 未开始（模块已占位） |
| v0.5+ | 后台保活 / 设置 / 诊断 / 合规上架 | ⬜ 未开始 |

功能清单与验收标准见 [REQUIREMENTS.md](./REQUIREMENTS.md)；**实现与需求的逐条对照**见 [docs/implementation-status.md](./docs/implementation-status.md)。

### 现在能跑通什么

- ✅ 首次启动 3 页引导：介绍 → 跳转「开发者选项 → 选择模拟位置应用」→ 申请定位权限（可跳过，状态持久化）
- ✅ 主地图页（osmdroid / OSM 瓦片）：长按选点、拖动微调、地址搜索（Nominatim）、回到当前位置
- ✅ 开始 / 停止模拟：gps + network 双 provider 注入，1 Hz，±3 m 随机抖动
- ✅ 顶部状态横幅：实时提示缺失权限 / 未授权 / 运行中 / 已选点
- ❌ 路线模拟、收藏、历史、设置、诊断页（均为空壳占位）

---

## 快速开始

### 环境要求

- JDK 17
- Android SDK（compileSdk 36 / targetSdk 36 / minSdk 29）
- Android Studio（推荐 Ladybug 或更新）

### 构建

```bash
# 1. 配置 SDK 路径（Android Studio 通常会自动生成 local.properties）
cp local.properties.example local.properties
# 编辑 local.properties，写入 sdk.dir=/path/to/Android/sdk

# 2. 构建 Debug APK
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 3. 安装到已连接设备
./gradlew :app:installDebug

# 4. 单元测试
./gradlew test
```

> **不需要任何 API Key。** 地图用 osmdroid（OpenStreetMap 瓦片），地址搜索用 Nominatim 公共 API。

### 首次使用（必做）

Android 6+ 强制要求人工授权，否则注入会被系统静默拒绝：

1. 打开设备的 **开发者选项**（设置 → 关于本机 → 连续点击「版本号」7 次）
2. 进入 **开发者选项 → 选择模拟位置应用** → 选择 **FakeLocation**
3. 回到 App，长按地图选点 → 点「开始模拟」
4. 打开任意地图类 App 验证位置已改变

App 内的引导页与状态横幅会提示当前缺哪一步。

---

## 项目结构

```
fake-location/
├── app/                          :app                    入口、根路由（引导/主页切换）
├── core/
│   ├── core-common/              :core:core-common       纯 Kotlin：LatLng、GeoUtils、MovementMode
│   ├── core-mock/                :core:core-mock         ★ 模拟位置引擎（gps+network 注入）
│   └── core-ui/                  :core:core-ui           Compose 主题与通用组件
├── data/
│   ├── data-local/               :data:data-local        Room + DataStore      【占位】
│   ├── data-remote/              :data:data-remote       网络数据源            【占位】
│   └── data-repository/          :data:data-repository   Repository 聚合       【占位】
├── feature/
│   ├── feature-onboarding/       ✅ 3 页引导
│   ├── feature-home/             ✅ 地图主页 + 单点定位
│   ├── feature-search/           ⬜ 占位
│   ├── feature-favorites/        ⬜ 占位
│   ├── feature-history/          ⬜ 占位
│   ├── feature-route/            ⬜ 占位
│   ├── feature-settings/         ⬜ 占位
│   └── feature-diagnostics/      ⬜ 占位
├── build-logic/convention/       Gradle Convention Plugin（构建规范统一入口）
├── docs/                         工程文档
├── gradle/libs.versions.toml     依赖版本唯一来源（Version Catalog）
├── REQUIREMENTS.md               产品需求文档
└── README.md
```

模块依赖规则、分层约定与「怎么加新模块」见 [docs/architecture.md](./docs/architecture.md)。

---

## 技术栈

| 维度 | 选型 |
|------|------|
| 语言 | Kotlin 1.9.24 |
| UI | Jetpack Compose + Material 3（BOM 2024.06.00） |
| 构建 | AGP 8.5.2 · Gradle Convention Plugin · Version Catalog |
| SDK | compileSdk 36 / targetSdk 36 / minSdk 29（Android 10） |
| DI | Hilt 2.51.1（kapt） |
| 异步 | Coroutines + Flow / StateFlow |
| 持久化 | DataStore Preferences（Room 依赖已备，待 v0.3 使用） |
| 地图 | osmdroid 6.1.20（OSM 瓦片，MIT，免 Key） |
| 地址搜索 | Nominatim（OSM 公共 API，免 Key，`HttpURLConnection`） |
| 测试 | JUnit 4 + kotlin-test |

---

## 核心原理

`app/src/main/java/.../core/core-mock/MockLocationEngineImpl.kt`：

1. `addTestProvider("gps")` / `addTestProvider("network")` —— **两个都注册**，因为部分 App 只读 `network`
2. `setTestProviderEnabled` + `setTestProviderStatus(AVAILABLE)` —— 部分 App 会读 provider 状态
3. 循环 `setTestProviderLocation()` —— 默认 1 Hz，位置带 ±3 m 随机抖动，避免被判定为「静止点」
4. `stop()` 时先 `cancelAndJoin()` 等待注入协程真正退出，再 `removeTestProvider` —— 否则会出现「provider 已移除但协程还在写入」的竞态
5. 启动前清理残留 provider —— 覆盖上次崩溃/被强杀导致的残留

未授权时 `addTestProvider` 抛 `SecurityException`，`MockAuthChecker` 用它反推授权状态（先清理残留 → 探测 → 再清理，不留痕迹）。

---

## 风险与限制

1. **必须人工授权**：Android 6+ 无法绕过「开发者选项 → 选择模拟位置应用」，App 只能引导用户去设置。
2. **不保证欺骗所有 App**：部分 App 通过 `Location.isFromMockProvider` 或服务端 IP/基站交叉校验识别虚拟定位。
3. **国内 ROM 差异**：MIUI / EMUI / HarmonyOS / ColorOS 对 mock provider 有额外限制，需逐个验证（尚未做兼容性矩阵）。
4. **jitter 只解决视觉层真实感**，对设备指纹 / 行为画像级别的反作弊无效。
5. **Release 包当前用 debug keystore 签名**，仅可内部安装，**不可上架**。上架前必做见 [docs/release-checklist.md](./docs/release-checklist.md)。

---

## 工程注意事项

- **新增依赖**：只改 `gradle/libs.versions.toml`，模块内用 `libs.xxx` 引用；`settings.gradle.kts` 已启用 `FAIL_ON_PROJECT_REPOS`，不要在模块里写 `repositories {}`。
- **新增模块**：`settings.gradle.kts` 加 `include`，并在 `app/build.gradle.kts` 加 `implementation(project(...))`，否则代码不会被编进去。
- **新 feature 模块**：直接套 `id("fakelocation.android.feature")` + `id("fakelocation.android.hilt")`，会自动带上 core-ui / core-common 与整套 Compose 依赖。
- **UI 文案**：目前 `HomeRoute` / `OnboardingRoute` 仍有硬编码中文，`strings.xml` 中的 key 未被使用。新增页面请走 `stringResource()`。

---

## 文档索引

| 文档 | 内容 |
|------|------|
| [REQUIREMENTS.md](./REQUIREMENTS.md) | 产品需求、验收标准、迭代里程碑 |
| [docs/architecture.md](./docs/architecture.md) | 模块架构、分层约定、扩展指南 |
| [docs/implementation-status.md](./docs/implementation-status.md) | 需求 vs 实现逐条对照、已知问题清单 |
| [docs/api-keys.md](./docs/api-keys.md) | 为什么没有 API Key，以及将来若引入时要怎么做 |
| [docs/release-checklist.md](./docs/release-checklist.md) | 上架前检查清单 |
