# 00 — 仓库扫描报告（Repository Scan）

> 生成时间：2026-09-18
> 扫描范围：`/Users/mk/mk/git/fake-location` 全量（排除 `build/`、`.gradle/` 产物）
> 用途：为后续新功能开发提供基线上下文

---

## 1. 项目类型与用途

| 项 | 结论 |
|----|------|
| 项目类型 | Android 原生 App（单 App，多模块 Gradle 工程） |
| 用途 | **虚拟定位工具**。基于 Android「开发者选项 → 选择模拟位置应用」机制，向系统 `gps` / `network` 两个 provider 持续注入伪造坐标，使其他 App（地图、打车、社交等）读到指定位置 |
| 核心价值 | 免 Root、免 API Key、操作直观 |
| 包名 / applicationId | `com.fakelocation` |
| 当前版本 | `versionName = 0.2.0`，`versionCode = 1` |
| 语言 | Kotlin（100%），UI 全部 Jetpack Compose |

---

## 2. 技术栈实测

| 维度 | 实际取值 | 来源 |
|------|---------|------|
| AGP | 8.5.2 | `gradle/libs.versions.toml` |
| Kotlin | 1.9.24 | 同上 |
| KSP | 1.9.24-1.0.20 | 同上 |
| Hilt | 2.51.1（`kapt`，非 KSP） | 同上 + `AndroidHiltConventionPlugin` |
| compileSdk / targetSdk / minSdk | **36 / 36 / 29** | `build-logic/.../KotlinConfig.kt`、`AndroidApplicationConventionPlugin.kt` |
| JVM target | 17 | `KotlinConfig.kt` |
| Compose BOM | 2024.06.00，compiler ext 1.5.14 | `libs.versions.toml`、convention plugin |
| Material3 | 1.2.1 | `libs.versions.toml` |
| Navigation | navigation-compose 2.7.7 + hilt-navigation-compose 1.2.0 | 同上 |
| 地图 | **osmdroid 6.1.20**（OSM 瓦片，免 Key） | `libs.versions.toml` |
| 地址搜索 | **Nominatim 公共 API + `HttpURLConnection`**（手写 JSON 解析） | `feature-home/HomeViewModel.kt` |
| 持久化 | DataStore Preferences（仅引导完成标记） | `OnboardingPreferences.kt` |
| Room | 依赖已声明，**代码中零使用** | `data-local/build.gradle.kts` |
| Retrofit / OkHttp / Moshi | 依赖已声明，**代码中零使用** | `libs.versions.toml` |
| 测试 | JUnit 4.13.2 + `kotlin-test`；无 MockK、无 Compose Test | `libs.versions.toml` |

### 构建基建（`build-logic`）

Convention Plugin（`includeBuild` 方式），6 个：

| Plugin ID | 作用 |
|-----------|------|
| `fakelocation.android.application` | application + kotlin-android + kapt，compileSdk 36 / targetSdk 36 / minSdk 29 |
| `fakelocation.android.library` | library 同基础配置 + `consumer-rules.pro` |
| `fakelocation.android.library.compose` | 在 library 基础上开启 Compose + 注入 Compose BOM 全套依赖 |
| `fakelocation.android.feature` | = `library.compose` + 自动依赖 `:core:core-ui`、`:core:core-common` |
| `fakelocation.android.hilt` | Hilt + kapt + hilt-android/compiler |
| `fakelocation.jvm.library` | 纯 JVM（当前无模块使用） |

> 依赖统一走 `gradle/libs.versions.toml` version catalog；`settings.gradle.kts` 使用 `FAIL_ON_PROJECT_REPOS`，禁止模块内自建仓库。

---

## 3. 模块结构与实现状态

Module 总数：**16**（`:app` + 3 core + 3 data + 8 feature + build-logic）

### 3.1 已实现（有真实业务代码）

| 模块 | 文件 | 状态 |
|------|------|------|
| `:app` | `FakeLocationApp.kt`、`MainActivity.kt`、`RootViewModel.kt` | ✅ 根路由：按 DataStore 引导状态切换 Onboarding / Home |
| `:core:core-common` | `LatLng`、`MovementMode`、`AppResult`、`GeoUtils` | ✅ 纯 Kotlin；Haversine 距离、bearing、角度归一化、km/h↔m/s |
| `:core:core-mock` | `MockLocationEngine(+Impl)`、`MockAuthChecker`、`MockConfig`、`MockState`、`JitterGenerator`、`LegacyLocationApi`、`MockModule` | ✅ **核心引擎完整**：gps+network 双 provider 注入、Mutex 串行化、`cancelAndJoin` 时序修复、残留 provider 清理、API 31+ / 29-30 分支、±3m 抖动 |
| `:core:core-ui` | `Theme`、`Color`、`Type`、`EmptyState` | ✅ Compose 主题 + 通用空状态 |
| `:feature:feature-onboarding` | `OnboardingRoute`、`OnboardingViewModel`、`OnboardingPreferences` | ✅ 3 页引导（介绍 → 跳开发者选项授权 → 申请定位权限），含跳过、动态 CTA、ON_RESUME 重检 |
| `:feature:feature-home` | `HomeRoute`、`HomeViewModel` | ✅ 单点定位 MVP：osmdroid 地图、长按选点、地址搜索（Nominatim）、当前位置 FAB、开始/停止模拟、状态横幅、Snackbar 错误 |

### 3.2 仅占位（空壳，无业务逻辑）

| 模块 | 占位文件 | 计划版本 |
|------|---------|---------|
| `:data:data-local` | `interface LocalDataSource`（空） | v0.3 |
| `:data:data-remote` | `interface RemoteDataSource`（空） | v0.3 |
| `:data:data-repository` | `interface FakeLocationRepository`（空） | v0.3 |
| `:feature:feature-search` | `object SearchPlaceholder` | v0.3 |
| `:feature:feature-favorites` | `object FavoritesPlaceholder` | v0.3 |
| `:feature:feature-history` | `object HistoryPlaceholder` | v0.3 |
| `:feature:feature-route` | `object RoutePlaceholder` | v0.4 |
| `:feature:feature-settings` | `object SettingsPlaceholder` | v0.6 |
| `:feature:feature-diagnostics` | `object DiagnosticsPlaceholder` | v0.7 |

> 注：`:data:*`、`:feature:feature-search` 等模块**未被 `:app` 依赖**（`app/build.gradle.kts` 仅依赖 core-ui、feature-onboarding、feature-home）。骨架存在但未接线。

### 3.3 但未在 `settings.gradle.kts` 中声明的模块

`REQUIREMENTS.md` 第 12 节提到的 `:core:core-location` **不存在**（settings 中无此 include，目录也不存在）。

---

## 4. 代码规模

| 指标 | 数值 |
|------|------|
| Kotlin 源文件（含 build-logic） | 43 |
| 主源码 Kotlin 文件（不含 build-logic、不含测试） | 32 |
| 测试文件 | 2（`GeoUtilsTest`、`JitterGeneratorTest`） |
| values-zh | 不存在（`values/` 即中文默认） |
| values-en | 存在（`feature-home`） |
| 已产出产物 | `app/build/outputs/apk/debug/app-debug.apk`、`.../release/app-release.apk` |

---

## 5. 开发流程与工程约束

| 项 | 实测 |
|----|------|
| 版本控制 | **未初始化 Git**（无 `.git` 目录），但 `.gitignore` 已存在 |
| CI/CD | 无（无 `.github/workflows`、无 `.gitlab-ci.yml`） |
| 代码规范工具 | 无 ktlint / detekt / spotless |
| Release 签名 | ⚠️ **使用 `debug.keystore` 签名**（`app/build.gradle.kts` 显式 `signingConfig = signingConfigs.getByName("debug")`），仅可内部安装，**不可上架** |
| R8/混淆 | release 已开 `minifyEnabled` + `shrinkResources`，存在 `proguard-rules.pro` |
| `local.properties` | 仅含 `sdk.dir`（`local.properties.example` 已提供模板） |
| 依赖注入 | Hilt，全部模块用 `kapt`（与 KSP 版本同时声明，但 Hilt 未启用 KSP） |

---

## 6. 文档现状

| 文件 | 状态 |
|------|------|
| `README.md` | ⚠️ 过期：指引「用 Android Studio 打开 `fakelocation/` 目录」，实际仓库根即项目根；项目结构只列了部分模块 |
| `REQUIREMENTS.md` | ⚠️ 与实现存在 10+ 处冲突（见 `docs/implementation-status.md`） |
| `docs/` | ❌ **不存在**，但 `REQUIREMENTS.md` 第 10 节引用了 `docs/api-keys.md`、`docs/release-checklist.md` |
| `local.properties.example` | ✅ 有效 |

---

## 7. 集成点（后续新功能的接入位置）

1. **新增业务模块** → `settings.gradle.kts` include + `app/build.gradle.kts` 加 `implementation(project(":feature:feature-xxx"))`；feature 模块直接用 `fakelocation.android.feature` + `fakelocation.android.hilt`。
2. **新增依赖** → 只改 `gradle/libs.versions.toml`，模块内用 `libs.xxx` 引用。
3. **导航扩展** → 当前 `MainActivity` 只有 onboarding/home 二元 switch，**没有 NavHost**；新增多页面需要先引入 Navigation-Compose 图（依赖已在 `app` 声明）。
4. **持久化落地** → `:data:data-local` 占位，Room 依赖已就绪；推荐 Room + DataStore，风格参考 `OnboardingPreferences`。
5. **引擎扩展（路线模拟）** → `MockState.RouteRunning(progress)` 已预留，`MovementMode` 已定义 4 种模式，`GeoUtils.bearing/haversine` 已可用；缺的是 polyline 插值器与 `MockLocationEngine` 的路线 API。
6. **网络层落地** → `:data:data-remote` 占位；现有实现是「ViewModel 内直连 Nominatim」，改造为 Repository 层需迁移。

---

## 8. 约束与风险

| 级别 | 内容 |
|------|------|
| P0 | 无 Git 版本控制 —— 任何改动都不可回滚，建议开发新功能前先 `git init` + 首次提交 |
| P0 | Release 用 debug 签名，产物不可上架 |
| P1 | `app` 未接线 `:data:*` 与 6 个占位 feature 模块，新增功能若跨模块需先补齐依赖关系 |
| P1 | UI 文案大量硬编码中文（`HomeRoute`、`OnboardingRoute/ViewModel`），`strings.xml` 中已定义的 key 并未被使用 → 国际化实际未生效 |
| P1 | 无 NavHost，单一二元路由；多页面功能需先建导航图 |
| P2 | 无静态检查工具（ktlint/detekt），无 CI |
| P2 | 测试覆盖率极低（仅 2 个纯函数单测：GeoUtils、JitterGenerator） |
| P2 | `MockLocationEngineImpl.injectLoop` 单点模式 `speed` 固定 0、`altitude` 固定 0，与 REQUIREMENTS 1.2 验收项不符 |
| P2 | 死代码残留：`GeoUtils.UNUSED`、`JitterGenerator.deg2rad/bearingShim` |
| P2 | `HomeViewModel.uiState` 用 7 参 `combine` + `values[i] as T` 强转，类型安全靠约定 |
| P3 | 厂商 ROM 差异（MIUI / EMUI / HarmonyOS）未做适配验证 |

---

## 9. 结论

这是个**已完成 v0.1 骨架 + v0.2 引导页、单点定位 MVP 可跑通**的 Android 虚拟定位 App。架构分层清晰（convention plugin 规范化、core/data/feature 三层），Mock 引擎是投入最深、质量最高的部分。**最大的问题是文档与实现脱节、没有版本控制、以及三层 data 层与 6 个 feature 模块全是空壳**——后续新功能大概率会落在这两个区域。
